package com.splicemachine.hbase.batch;

import com.carrotsearch.hppc.BitSet;
import com.splicemachine.si.api.RollForwardQueue;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Scott Fines
 * Created on: 4/30/13
 */
public interface WriteContextFactory<T> {

    WriteContext create(String txnId,T key) throws IOException, InterruptedException;

    WriteContext create(String txnId,T key,
                        RollForwardQueue<byte[],ByteBuffer> queue,
                        int expectedWrites) throws IOException, InterruptedException;

    /**
     * Creates a context that only updates side effects.
     * @param key
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    WriteContext createPassThrough(String txnid,T key,int expectedWrites) throws IOException,InterruptedException;

    void dropIndex(long indexConglomId);

    void addIndex(long indexConglomId, BitSet indexedColumns, int[] mainColToIndexPosMap, boolean unique,BitSet descColumns);
}
