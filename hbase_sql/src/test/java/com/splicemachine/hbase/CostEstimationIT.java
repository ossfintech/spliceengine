/*
 * Copyright (c) 2012 - 2019 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.hbase;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceUnitTest;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.test_tools.TableCreator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;

/**
 * Created by yxia on 5/16/17.
 */
public class CostEstimationIT extends SpliceUnitTest {
    public static final String CLASS_NAME = CostEstimationIT.class.getSimpleName().toUpperCase();
    protected static SpliceWatcher spliceClassWatcher = new SpliceWatcher(CLASS_NAME);
    protected static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(CLASS_NAME);

    @ClassRule
    public static TestRule chain = RuleChain.outerRule(spliceClassWatcher)
            .around(spliceSchemaWatcher);
    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(CLASS_NAME);

    public static void createData(Connection conn, String schemaName) throws Exception {

        new TableCreator(conn)
                .withCreate("create table t1 (a1 int, b1 int, c1 int)")
                .withInsert("insert into t1 values(?,?,?)")
                .withRows(rows(
                        row(1,1,1),
                        row(2,2,2),
                        row(3,3,3),
                        row(4,4,4),
                        row(5,5,5),
                        row(6,6,6),
                        row(7,7,7),
                        row(8,8,8),
                        row(9,9,9),
                        row(10,10,10)))
                .create();
        for (int i = 0; i < 2; i++) {
            spliceClassWatcher.executeUpdate("insert into t1 select * from t1");
        }

        new TableCreator(conn)
                .withCreate("create table t2 (a2 int, b2 int, c2 int, constraint con1 primary key (a2))")
                .withInsert("insert into t2 values(?,?,?)")
                .withRows(rows(
                        row(1,1,1),
                        row(2,1,1),
                        row(3,1,1),
                        row(4,1,1),
                        row(5,1,1),
                        row(6,2,2),
                        row(7,2,2),
                        row(8,2,2),
                        row(9,2,2),
                        row(10,2,2)))
                .create();

        int factor = 10;
        for (int i = 1; i <= 2; i++) {
            spliceClassWatcher.executeUpdate(format("insert into t2 select a2+%d, b2,c2 from t2", factor));
            factor = factor * 2;
        }

        /* split the table at the value 20 */
        spliceClassWatcher.executeUpdate(format("CALL SYSCS_UTIL.SYSCS_SPLIT_TABLE_OR_INDEX_AT_POINTS('%s', '%s', null, '%s')",
                spliceSchemaWatcher.toString(), "T2", "\\x94"));


        new TableCreator(conn)
                .withCreate("create table t3 (a3 int, b3 int, c3 int, d3 int, primary key (a3, c3))")
                .withInsert("insert into t3 values(?,?,?,?)")
                .withRows(rows(
                        row(1,1,1,1),
                        row(1,2,2,2),
                        row(1,3,3,3),
                        row(1,4,4,4),
                        row(1,5,5,5),
                        row(1,6,6,6),
                        row(1,7,7,7),
                        row(1,8,8,8),
                        row(1,9,9,9),
                        row(1,10,10,10)))
                .create();

        factor = 10;
        for (int i = 1; i <= 8; i++) {
            spliceClassWatcher.executeUpdate(format("insert into t3 select a3, b3+%1$d,c3+%1$d, d3 from t3", factor));
            factor = factor * 2;
        }

        new TableCreator(conn)
                .withCreate("create table t4 (a4 int, b4 int, c4 int, d4 int, primary key (a4, b4))")
                .create();

        spliceClassWatcher.executeUpdate("insert into t4 select * from t3");

        try(PreparedStatement ps = spliceClassWatcher.getOrCreateConnection().
                prepareStatement("analyze schema " + CLASS_NAME)) {
            ps.execute();
        }

        // create more tables that use dummy stats
        new TableCreator(conn)
                .withCreate("create table t11 (a1 int, b1 int, c1 int)")
                .create();
        new TableCreator(conn)
                .withCreate("create table t22 (a2 int, b2 int, c2 int)")
                .create();
        new TableCreator(conn)
                .withCreate("create table t33 (a3 int, b3 int, c3 int)")
                .create();

        conn.commit();
    }

    @BeforeClass
    public static void createDataSet() throws Exception {
        createData(spliceClassWatcher.getOrCreateConnection(), spliceSchemaWatcher.toString());
    }

    @Test
    public void testCardinalityAfterTableSplit() throws Exception {
        SConfiguration config = HConfiguration.getConfiguration();
        HBaseTestingUtility testingUtility = new HBaseTestingUtility((Configuration) config.getConfigSource().unwrapDelegate());
        HBaseAdmin admin = testingUtility.getHBaseAdmin();
        TableName tableName = TableName.valueOf(config.getNamespace(),
                Long.toString(TestUtils.baseTableConglomerateId(spliceClassWatcher.getOrCreateConnection(),
                        spliceSchemaWatcher.toString(), "T2")));

        List<HRegionInfo> regions = admin.getTableRegions(tableName);
        int size1 = regions.size();

        if (size1 >= 2) {
            // expect number of partitions to be at least 2 if table split happens
            String sqlText = "explain select * from --splice-properties joinOrder=fixed \n" +
                    "t1, t2 --splice-properties joinStrategy=NESTEDLOOP \n" +
                    "where c1=c2";

            double outputRows = parseOutputRows(getExplainMessage(4, sqlText, methodWatcher));
            Assert.assertTrue(format("OutputRows is expected to be greater than 1, actual is %s", outputRows), outputRows > 1);

        /* split the table at value 30 */
            methodWatcher.executeUpdate(format("CALL SYSCS_UTIL.SYSCS_SPLIT_TABLE_OR_INDEX_AT_POINTS('%s', '%s', null, '%s')",
                    spliceSchemaWatcher.toString(), "T2", "\\x9E"));

            regions = admin.getTableRegions(tableName);
            int size2 = regions.size();

            if (size2 >= 3) {
                // expect number of partitions to be at least 3 if table split happens
                /**The two newly split partitions do not have stats. Ideally, we should re-collect stats,
                 * but if we haven't, explain should reflect the stats from the remaining partitions.
                 * For current test case, t2 has some partition stats missing, without the fix of SPLICE-1452,
                 * its cardinality estimation assumes unique for all non-null rows, which is too conservative,
                 * so we end up estimating 1 output row from t2 for each outer table row from t1.
                 * With SPLICE-1452's fix, we should see a higher number for the output row from t2.
                 */
                outputRows = parseOutputRows(getExplainMessage(4, sqlText, methodWatcher));
                Assert.assertTrue(format("OutputRows is expected to be greater than 1, actual is %s", outputRows), outputRows > 1);
            }
        }
    }

    @Test
    public void testOuterJoinRowCount() throws Exception {
        /*  t11 is hinted to have a total rowcount of 300, t22 and t33 have the default rowcount of 20.
            The plan is similar to the following:
            --------------------------------------------------------------------
            Cursor(n=10,rows=1,updateMode=READ_ONLY (1),engine=control)
              ->  ScrollInsensitive(n=9,totalCost=131.717,outputRows=1,outputHeapSize=0 B,partitions=1)
                ->  ProjectRestrict(n=8,totalCost=29.575,outputRows=1,outputHeapSize=0 B,partitions=1)
                  ->  GroupBy(n=7,totalCost=29.575,outputRows=1,outputHeapSize=0 B,partitions=1)
                    ->  ProjectRestrict(n=6,totalCost=24.84,outputRows=219,outputHeapSize=296 B,partitions=1)
                      ->  BroadcastJoin(n=5,totalCost=24.84,outputRows=219,outputHeapSize=296 B,partitions=1,preds=[(A1[8:1] = A2[8:2])])
                        ->  BroadcastLeftOuterJoin(n=4,totalCost=12.28,outputRows=18,outputHeapSize=78 B,partitions=1,preds=[(A2[6:1] = A3[6:2])])
                          ->  TableScan[T33(1920)](n=3,totalCost=4.04,scannedRows=20,outputRows=20,outputHeapSize=78 B,partitions=1)
                          ->  TableScan[T22(1904)](n=2,totalCost=4.04,scannedRows=20,outputRows=18,outputHeapSize=18 B,partitions=1,preds=[(A2[2:1] = 90)])
                        ->  TableScan[T11(1888)](n=1,totalCost=4.6,scannedRows=300,outputRows=270,outputHeapSize=270 B,partitions=1,preds=[(A1[0:1] = 90)])

            10 rows selected
         */
        rowContainsQuery(new int[]{2,3,4,5,6,7,8,9,10},"explain select count(*) from --splice-properties joinOrder=fixed\n" +
                        "t11  --splice-properties useDefaultRowCount=300\n" +
                        ", t22 left join t33 --splice-properties joinStrategy=broadcast\n" +
                        "on a2=a3 where a1=a2 and a1=90", methodWatcher,
                "outputRows=1", "outputRows=1", "outputRows=1", "outputRows=219", "outputRows=219", "outputRows=18", "outputRows=20", "outputRows=18", "outputRows=270");

    }

    @Test
    public void testMergeJoinWithVeryNonUniqueJoinCondition() throws Exception {
        // though both source tables are sorted on X.a4=Y.a4, a4 is a very non-unique column (in this case, it has only one value).
        // c4 is a very unique column, but it is not part of the PK that merge join can make use of, so merge join is not attractive
        // for this query
        thirdRowContainsQuery("explain select * from t4 as X, t4 as Y where X.a4=Y.a4 and X.c4=Y.c4","BroadcastJoin",methodWatcher);
    }

    @Test
    public void testMergeJoinWithVeryUniqueJoinCondition() throws Exception {
        thirdRowContainsQuery("explain select * from t3 as X, t3 as Y where X.a3=Y.a3 and X.c3=Y.c3","MergeJoin",methodWatcher);
    }
}
