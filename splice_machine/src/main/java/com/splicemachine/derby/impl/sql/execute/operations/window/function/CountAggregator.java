package com.splicemachine.derby.impl.sql.execute.operations.window.function;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.ClassFactory;
import com.splicemachine.db.iapi.sql.execute.WindowFunction;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.SQLLongint;

/**
 * Created by jyuan on 7/31/14.
 */
public class CountAggregator extends SpliceGenericWindowFunction {
    public WindowFunction setup( ClassFactory cf, String aggregateName, DataTypeDescriptor returnType ) {
        super.setup( cf, aggregateName, returnType );
        return this;
    }

    @Override
    public void accumulate(DataValueDescriptor[] valueDescriptors) throws StandardException {
        this.add(valueDescriptors);
    }

    @Override
    protected void calculateOnAdd(SpliceGenericWindowFunction.WindowChunk chunk, DataValueDescriptor[] dvds) throws StandardException{
        DataValueDescriptor result = chunk.getResult();
        if (result == null || result.isNull()) {
            SQLLongint r = new SQLLongint(1);
            chunk.setResult(r);
        } else {
            long count = result.getLong();
            result.setValue(count+1);
            chunk.setResult(result);
        }
    }

    @Override
    protected void calculateOnRemove(SpliceGenericWindowFunction.WindowChunk chunk, DataValueDescriptor[] dvds) throws StandardException {
        DataValueDescriptor result = chunk.getResult();
        long count = result.getLong();
        result.setValue(count-1);
        chunk.setResult(result);
    }

    public DataValueDescriptor getResult() throws StandardException {
        // Iterate through each chunk, compute the max/min of each chunk
        long count = 0;
        for (WindowChunk chunk : chunks) {
            count += chunk.getResult().getLong();
        }
        return new SQLLongint(count);
    }

    public WindowFunction newWindowFunction() {
        return new CountAggregator();
    }
}
