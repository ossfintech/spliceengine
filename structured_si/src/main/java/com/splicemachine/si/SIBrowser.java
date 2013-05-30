package com.splicemachine.si;

import com.splicemachine.constants.SIConstants;
import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.si.impl.TransactionStatus;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SIBrowser extends SIConstants{
    public static void main(String[] args) throws IOException {
        HTable transactionTable = new HTable(TRANSACTION_TABLE);
        Scan scan = new Scan();
        ResultScanner scanner = transactionTable.getScanner(scan);
        Iterator<Result> results = scanner.iterator();
        Map<Long, Object> x = new HashMap<Long, Object>();
        Long idToFind = null;
        //idToFind = 62L;
        Result toFind = null;
        while (results.hasNext()) {
            Result r = results.next();
            final byte[] value = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_START_TIMESTAMP_COLUMN_BYTES);
            final long beginTimestamp = Bytes.toLong(value);
            final byte[] statusValue = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_STATUS_COLUMN_BYTES);
            TransactionStatus status = null;
            if (statusValue != null) {
                status = TransactionStatus.values()[Bytes.toInt(statusValue)];
            }
            final byte[] endValue = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_COMMIT_TIMESTAMP_COLUMN_BYTES);
            Long commitTimestamp = null;
            if (endValue != null) {
                commitTimestamp = Bytes.toLong(endValue);
            }
            final byte[] parentValue = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_PARENT_COLUMN_BYTES);
            Long parent = null;
            if (parentValue != null) {
                parent = Bytes.toLong(parentValue);
            }
            final byte[] dependentValue = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_DEPENDENT_COLUMN_BYTES);
            Boolean dependent = null;
            if (dependentValue != null) {
                dependent = Bytes.toBoolean(dependentValue);
            }
            final byte[] writesValue = r.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_ALLOW_WRITES_COLUMN_BYTES);
            Boolean writes = null;
            if (writesValue != null) {
                writes = Bytes.toBoolean(writesValue);
            }
            final KeyValue keepAlive = r.getColumnLatest(TRANSACTION_FAMILY_BYTES, TRANSACTION_KEEP_ALIVE_COLUMN_BYTES);
            String keepAliveValue = null;
            if (keepAlive != null) {
                keepAliveValue = new Timestamp(keepAlive.getTimestamp()).toString();
            }
            x.put(beginTimestamp, new Object[]{parent, dependent, writes, status, commitTimestamp, keepAliveValue});
            if (idToFind != null && beginTimestamp == idToFind) {
                toFind = r;
            }
            //System.out.println(beginTimestamp + " " + status + " " + commitTimestamp);
        }

        if (toFind != null) {
            System.out.println("killing transaction " + idToFind);
            Put put = new Put(toFind.getRow());
            KeyValue kv = new KeyValue(toFind.getRow(), TRANSACTION_FAMILY_BYTES, TRANSACTION_STATUS_COLUMN_BYTES,
                    Bytes.toBytes(TransactionStatus.ERROR.ordinal()));
            put.add(kv);
            transactionTable.put(put);
        } else {
            final ArrayList<Long> list = new ArrayList<Long>(x.keySet());
            Collections.sort(list);
            System.out.println("transaction parent dependent writesAllowed status commitTimestamp keepAliveTimestamp");
            for (Long k : list) {
                Object[] v = (Object[]) x.get(k);
                System.out.println(k + " " + v[0] + " " + v[1] + " " + v[2] + " " + v[3] + " " + v[4] + " " + v[5]);
            }

            //dumpTable("conglomerates", "16");
            //dumpTable("SYCOLUMNS_INDEX2", "161");

            //dumpTable("p", "SPLICE_CONGLOMERATE");
            //dumpTable("p2", "1184");
        }
    }

    private static void dumpTable(String tableName, String tableId) throws IOException {
        Scan scan;
        ResultScanner scanner;
        Iterator<Result> results;
        System.out.println("------------------");
        System.out.println(tableName);
        HTable cTable = new HTable(tableId);
        scan = new Scan();
        scan.setMaxVersions();
        scanner = cTable.getScanner(scan);
        results = scanner.iterator();
        int i = 0;
        while (results.hasNext()) {
            Result r = results.next();
            final byte[] row = r.getRow();
            final List<KeyValue> list = r.list();
            for (KeyValue kv : list) {
                final byte[] f = kv.getFamily();
                String family = Bytes.toString(f);
                final byte[] q = kv.getQualifier();
                final byte[] v = kv.getValue();
                final long ts = kv.getTimestamp();
                if (Arrays.equals(SNAPSHOT_ISOLATION_FAMILY_BYTES, f)
                        && Arrays.equals(SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES, q)) {
                    Long timestamp = null;
                    if (v.length > 0) {
                        timestamp = Bytes.toLong(v);
                    }
                    System.out.println("timestamp " + timestamp + " @ " + ts);
                } else {
                    String v2 = Bytes.toString(v);
                    char qualifier = (char) q[0];
                    String byteValue = "" + v.length + "[";
                    for (byte b : v) {
                        if (b >= 32 && b <= 122) {
                            char c = (char) b;
                            byteValue += c;
                        } else {
                            byteValue += b;
                            byteValue += " ";
                        }
                    }
                    byteValue += "]";
                    System.out.println(Bytes.toString(row) + " " + family + " " + qualifier + " @ " + ts + " " + byteValue + " " + v2);
                }
            }
            i++;
            System.out.println(i + " " + row + " " + BytesUtil.debug(row));
        }
    }
}
