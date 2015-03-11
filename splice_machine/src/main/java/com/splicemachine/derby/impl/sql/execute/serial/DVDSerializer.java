package com.splicemachine.derby.impl.sql.execute.serial;

import com.splicemachine.db.iapi.types.DataValueDescriptor;

import java.nio.ByteBuffer;

public interface DVDSerializer {

		public void deserialize(DataValueDescriptor ldvd,byte[] bytes, int offset, int length,boolean desc) throws Exception;

    public byte[] serialize(DataValueDescriptor obj) throws Exception;
		public byte[] serialize(DataValueDescriptor obj,boolean desc) throws Exception;
}
