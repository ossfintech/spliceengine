package com.splicemachine.si.impl;

import com.google.common.cache.Cache;
import com.splicemachine.si.data.api.SDataLib;
import com.splicemachine.si.data.api.SGet;
import com.splicemachine.si.data.api.STable;
import com.splicemachine.si.data.api.STableReader;
import com.splicemachine.si.data.api.STableWriter;
import com.splicemachine.si.api.TransactionId;
import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library of functions used by the SI module when accessing the transaction table. Encapsulates low-level data access
 * calls so the other classes can be expressed at a higher level.
 */
public class TransactionStore {
    static final Logger LOG = Logger.getLogger(TransactionStore.class);

    private final SDataLib dataLib;
    private final STableReader reader;
    private final Cache<Long, ImmutableTransaction> immutableTransactionCache;
    private final Cache<Long, ActiveTransactionCacheEntry> activeTransactionCache;
    private final Cache<Long, Transaction> transactionCache;
    private final STableWriter writer;

    private final TransactionSchema transactionSchema;
    private final TransactionSchema encodedSchema;
    private int waitForCommittingMS;

    public TransactionStore(TransactionSchema transactionSchema, SDataLib dataLib,
                            STableReader reader, STableWriter writer,
                            Cache<Long, ImmutableTransaction> immutableTransactionCache, Cache<Long, ActiveTransactionCacheEntry> activeTransactionCache,
                            Cache<Long, Transaction> transactionCache, int waitForCommittingMS) {
        this.transactionSchema = transactionSchema;
        this.encodedSchema = transactionSchema.encodedSchema(dataLib);
        this.dataLib = dataLib;
        this.reader = reader;
        this.activeTransactionCache = activeTransactionCache;
        this.transactionCache = transactionCache;
        this.immutableTransactionCache = immutableTransactionCache;
        this.writer = writer;
        this.waitForCommittingMS = waitForCommittingMS;
    }

    public void recordNewTransaction(TransactionId startTransactionTimestamp, TransactionParams params,
                                     TransactionStatus status, TransactionStatus localStatus) throws IOException {
        writePut(makeCreateTuple(startTransactionTimestamp, params, status, localStatus));
    }

    public void addChildToTransaction(TransactionId transactionId, TransactionId childTransactionId) throws IOException {
        Object put = makeBasePut(transactionId);
        dataLib.addKeyValueToPut(put, encodedSchema.siChildrenFamily, dataLib.encode(childTransactionId.getTransactionIdString()), null,
                dataLib.encode(true));
        writePut(put);
    }

    public boolean recordTransactionEnd(TransactionId startTransactionTimestamp, long commitTransactionTimestamp,
                                        TransactionStatus expectedStatus, TransactionStatus newStatus, boolean local) throws IOException {
        Tracer.traceStatus(startTransactionTimestamp.getId(), newStatus, true);
        try {
            return writePut(makeCommitPut(startTransactionTimestamp, commitTransactionTimestamp, newStatus),
                    (expectedStatus == null) ? null : encodedStatus(expectedStatus), local);
        } finally {
            Tracer.traceStatus(startTransactionTimestamp.getId(), newStatus, false);
        }
    }

    private Object encodedStatus(TransactionStatus status) {
        if (status == null) {
            return encodedSchema.siNull;
        } else {
            return dataLib.encode(status.ordinal());
        }
    }

    public boolean recordTransactionStatusChange(TransactionId startTransactionTimestamp, TransactionStatus expectedStatus,
                                                 TransactionStatus newStatus, boolean local)
            throws IOException {
        Tracer.traceStatus(startTransactionTimestamp.getId(), newStatus, true);
        try {
            return writePut(makeStatusUpdateTuple(startTransactionTimestamp, newStatus, local), encodedStatus(expectedStatus), local);
        } finally {
            Tracer.traceStatus(startTransactionTimestamp.getId(), newStatus, false);
        }
    }

    public void recordKeepAlive(TransactionId startTransactionTimestamp)
            throws IOException {
        writePut(makeKeepAliveTuple(startTransactionTimestamp));
    }

    public ImmutableTransaction getImmutableTransaction(long beginTimestamp) throws IOException {
        return getImmutableTransaction(new SITransactionId(beginTimestamp));
    }

    public ImmutableTransaction getImmutableTransaction(TransactionId transactionId) throws IOException {
        final Transaction cachedTransaction = transactionCache.getIfPresent(transactionId.getId());
        if (cachedTransaction != null) {
            return cachedTransaction;
        }
        ImmutableTransaction immutableCachedTransaction = immutableTransactionCache.getIfPresent(transactionId.getId());
        if (immutableCachedTransaction != null) {
            return immutableCachedTransaction;
        }
        immutableCachedTransaction = getImmutableTransactionDirect(transactionId);
        immutableTransactionCache.put(transactionId.getId(), immutableCachedTransaction);
        return immutableCachedTransaction;
    }

    public Transaction getTransaction(long beginTimestamp) throws IOException {
        return getTransactionDirect(new SITransactionId(beginTimestamp), false);
    }

    public Transaction getTransaction(TransactionId transactionId) throws IOException {
        return getTransactionDirect(transactionId, false);
    }

    public Transaction getImmutableTransactionDirect(TransactionId transactionId) throws IOException {
        return getTransactionDirect(transactionId, true);
    }

    public Transaction getTransactionAsOf(long beginTimestamp, TransactionId perspective) throws IOException {
        TransactionId transactionId = new SITransactionId(beginTimestamp);
        final Transaction cachedTransaction = transactionCache.getIfPresent(transactionId.getId());
        if (cachedTransaction != null) {
            return cachedTransaction;
        }
        final ActiveTransactionCacheEntry activeEntry = activeTransactionCache.getIfPresent(transactionId.getId());
        if (activeEntry != null && activeEntry.effectiveTimestamp >= perspective.getId()) {
            return activeEntry.transaction;
        }
        final Transaction transaction = loadTransaction(transactionId, false);
        activeTransactionCache.put(transactionId.getId(), new ActiveTransactionCacheEntry(perspective.getId(), transaction));
        return transaction;
    }

    private Transaction getTransactionDirect(TransactionId transactionId, boolean immutableOnly) throws IOException {
        final Transaction cachedTransaction = transactionCache.getIfPresent(transactionId.getId());
        if (cachedTransaction != null) {
            //LOG.warn("cache HIT " + transactionId.getTransactionIdString());
            return cachedTransaction;
        }
        return loadTransaction(transactionId, immutableOnly);
    }

    private Transaction loadTransaction(TransactionId transactionId, boolean immutableOnly) throws IOException {
        if (immutableOnly) {
           return loadTransactionDirect(transactionId);
        } else {
            TransactionId rootId = new SITransactionId(getImmutableTransaction(transactionId).getRootBeginTimestamp());

            Transaction transaction = loadTransactionDirect(rootId);
            if (transaction.isCommitting()) {
                try {
                    Tracer.traceWaiting(transactionId.getId());
                    Thread.sleep(waitForCommittingMS);
                } catch (InterruptedException e) {
                    //ignore this
                }
                transaction = loadTransactionDirect(rootId);
                if (transaction.isCommitting()) {
                    throw new DoNotRetryIOException("Transaction is committing: " + transactionId.getTransactionIdString());
                }
            }
            if (rootId.getTransactionIdString().equals(transactionId.getTransactionIdString())) {
                return transaction;
            } else {
                return loadTransactionDirect(transactionId);
            }
        }
    }

    private Transaction loadTransactionDirect(TransactionId transactionId) throws IOException {
        Object tupleKey = dataLib.newRowKey(new Object[]{transactionIdToRowKey(transactionId)});

        STable transactionSTable = reader.open(transactionSchema.tableName);
        try {
            SGet get = dataLib.newGet(tupleKey, null, null, null);
            Object resultTuple = reader.get(transactionSTable, get);
            if (resultTuple != null) {

                final List keepAliveValues = dataLib.getResultColumn(resultTuple, encodedSchema.siFamily, encodedSchema.keepAliveQualifier);
                final Object keepAliveValue = keepAliveValues.get(0);
                final long keepAlive = dataLib.getKeyValueTimestamp(keepAliveValue);

                TransactionStatus status = getTransactionStatusField(resultTuple, encodedSchema.statusQualifier);
                TransactionStatus localStatus = getTransactionStatusField(resultTuple, encodedSchema.localStatusQualifier);
                Long parentId = getLongField(resultTuple, encodedSchema.parentQualifier);
                Transaction parent = null;
                if (parentId != null) {
                    parent = getTransaction(parentId);
                }
                Long commitTimestamp = getLongField(resultTuple, encodedSchema.commitQualifier);
                Long localCommitTimestamp = getLongField(resultTuple, encodedSchema.localCommitQualifier);
                Map childrenMap = dataLib.getResultFamilyMap(resultTuple, encodedSchema.siChildrenFamily);
                Set<Long> children = new HashSet<Long>();
                for (Object child : childrenMap.keySet()) {
                    children.add(Long.valueOf((String) dataLib.decode(child, String.class)));
                }

                final Transaction result = new Transaction(transactionId.getId(), keepAlive, parent, children,
                        getBooleanFieldFromResult(resultTuple, encodedSchema.dependentQualifier),
                        getBooleanFieldFromResult(resultTuple, encodedSchema.allowWritesQualifier),
                        getBooleanFieldFromResult(resultTuple, encodedSchema.readUncommittedQualifier),
                        getBooleanFieldFromResult(resultTuple, encodedSchema.readCommittedQualifier),
                        status, commitTimestamp, localStatus, localCommitTimestamp);
                if (!result.isActive()) {
                    transactionCache.put(transactionId.getId(), result);
                    //LOG.warn("cache PUT " + transactionId.getTransactionIdString());
                } else {
                    //LOG.warn("cache NOT " + transactionId.getTransactionIdString());
                }
                return result;
            }
        } finally {
            reader.close(transactionSTable);
        }
        throw new RuntimeException("transaction ID not found");
    }

    private Long getLongField(Object resultTuple, Object commitQualifier) {
        final Object commitValue = dataLib.getResultValue(resultTuple, encodedSchema.siFamily, commitQualifier);
        Long commitTimestamp = null;
        if (commitValue != null) {
            commitTimestamp = (Long) dataLib.decode(commitValue, Long.class);
        }
        return commitTimestamp;
    }

    private TransactionStatus getTransactionStatusField(Object resultTuple, Object statusQualifier) {
        final Object statusValue = dataLib.getResultValue(resultTuple, encodedSchema.siFamily, statusQualifier);
        return (statusValue == null) ? null : TransactionStatus.values()[((Integer) dataLib.decode(statusValue, Integer.class))];
    }

    private Boolean getBooleanFieldFromResult(Object resultTuple, Object qualifier) {
        final Object value = dataLib.getResultValue(resultTuple, encodedSchema.siFamily, qualifier);
        Boolean result = null;
        if (value != null) {
            result = (Boolean) dataLib.decode(value, Boolean.class);
        }
        return result;
    }

    private Object makeStatusUpdateTuple(TransactionId transactionId, TransactionStatus newStatus, boolean local) {
        Object put = makeBasePut(transactionId);
        addFieldToPut(put, local ? encodedSchema.localStatusQualifier : encodedSchema.statusQualifier, newStatus.ordinal());
        return put;
    }

    private Object makeKeepAliveTuple(TransactionId transactionId) {
        Object put = makeBasePut(transactionId);
        addFieldToPut(put, encodedSchema.keepAliveQualifier, encodedSchema.siNull);
        return put;
    }

    private Object makeCreateTuple(TransactionId transactionId, TransactionParams params, TransactionStatus status,
                                   TransactionStatus localStatus) {
        Object put = makeBasePut(transactionId);
        addFieldToPut(put, encodedSchema.startQualifier, transactionId.getId());
        addFieldToPut(put, encodedSchema.keepAliveQualifier, encodedSchema.siNull);
        if (params.parent != null) {
            addFieldToPut(put, encodedSchema.parentQualifier, params.parent.getId());
        }
        if (params.dependent != null) {
            addFieldToPut(put, encodedSchema.dependentQualifier, params.dependent);
        }
        addFieldToPut(put, encodedSchema.allowWritesQualifier, params.allowWrites);
        if (params.readUncommitted != null) {
            addFieldToPut(put, encodedSchema.readUncommittedQualifier, params.readUncommitted);
        }
        if (params.readCommitted != null) {
            addFieldToPut(put, encodedSchema.readCommittedQualifier, params.readCommitted);
        }
        if (status != null) {
            addFieldToPut(put, encodedSchema.statusQualifier, status.ordinal());
        }
        if (localStatus != null) {
            addFieldToPut(put, encodedSchema.localStatusQualifier, localStatus.ordinal());
        }
        return put;
    }

    private Object makeCommitPut(TransactionId transactionId, long commitTransactionTimestamp, TransactionStatus newStatus) {
        Object put = makeBasePut(transactionId);
        addFieldToPut(put, encodedSchema.commitQualifier, commitTransactionTimestamp);
        addFieldToPut(put, encodedSchema.statusQualifier, newStatus.ordinal());
        return put;
    }

    private Object makeBasePut(TransactionId transactionId) {
        Object rowKey = dataLib.newRowKey(new Object[]{transactionIdToRowKey(transactionId)});
        return dataLib.newPut(rowKey);
    }

    private long transactionIdToRowKey(TransactionId transactionId) {
        byte[] result = Bytes.toBytes(transactionId.getId());
        ArrayUtils.reverse(result);
        return Bytes.toLong(result);
    }

    private void addFieldToPut(Object put, Object qualifier, Object value) {
        dataLib.addKeyValueToPut(put, encodedSchema.siFamily, qualifier, null, dataLib.encode(value));
    }

    private void writePut(Object put) throws IOException {
        writePut(put, null, false);
    }

    private boolean writePut(Object put, Object expectedStatus, boolean local) throws IOException {
        final STable transactionSTable = reader.open(transactionSchema.tableName);
        try {
            if (expectedStatus == null) {
                writer.write(transactionSTable, put);
                return true;
            } else {
                return writer.checkAndPut(transactionSTable, encodedSchema.siFamily,
                        local ? encodedSchema.localStatusQualifier : encodedSchema.statusQualifier,
                        expectedStatus, put);
            }
        } finally {
            reader.close(transactionSTable);
        }
    }
}
