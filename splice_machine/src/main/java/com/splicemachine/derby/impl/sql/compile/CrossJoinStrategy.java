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

package com.splicemachine.derby.impl.sql.compile;

import com.splicemachine.EngineDriver;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.*;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.impl.sql.compile.*;
import com.splicemachine.utils.SpliceLogUtils;

public class CrossJoinStrategy extends BaseJoinStrategy {
    public CrossJoinStrategy() { }

    /**
     * @see JoinStrategy#getName
     */
    public String getName() {
        return "CROSS";
    }


    /**
     * @see JoinStrategy#joinResultSetMethodName
     */
	@Override
    public String joinResultSetMethodName() {
        return "getCrossJoinResultSet";
    }

    /**
     * @see JoinStrategy#halfOuterJoinResultSetMethodName
     */
	@Override
    public String halfOuterJoinResultSetMethodName() {
        return "getBroadcastLeftOuterJoinResultSet";
    }



    @Override
    public int getScanArgs(
            TransactionController tc,
            MethodBuilder mb,
            Optimizable innerTable,
            OptimizablePredicateList storeRestrictionList,
            OptimizablePredicateList nonStoreRestrictionList,
            ExpressionClassBuilderInterface acbi,
            int bulkFetch,
            MethodBuilder resultRowAllocator,
            int colRefItem,
            int indexColItem,
            int lockMode,
            boolean tableLocked,
            int isolationLevel,
            int maxMemoryPerTable,
            boolean genInListVals, String tableVersion, boolean pin,
            int splits,
            String delimited,
            String escaped,
            String lines,
            String storedAs,
            String location,
            int partitionRefItem
    ) throws StandardException {
        ExpressionClassBuilder acb = (ExpressionClassBuilder) acbi;
        int numArgs;
        /* If we're going to generate a list of IN-values for index probing
         * at execution time then we push TableScanResultSet arguments plus
         * three additional arguments: 1) the list of IN-list values, and 2)
         * a boolean indicating whether or not the IN-list values are already
         * sorted, 3) the in-list column position in the index or primary key.
         */
        if (genInListVals) {
            numArgs = 38;
        }
        else {
            numArgs = 35 ;
        }

        fillInScanArgs1(tc, mb, innerTable, storeRestrictionList, acb, resultRowAllocator);
        if (genInListVals)
            ((PredicateList)storeRestrictionList).generateInListValues(acb, mb);

        if (SanityManager.DEBUG) {
            /* If we're not generating IN-list values with which to probe
             * the table then storeRestrictionList should not have any
             * IN-list probing predicates.  Make sure that's the case.
             */
            if (!genInListVals) {
                Predicate pred = null;
                for (int i = storeRestrictionList.size() - 1; i >= 0; i--) {
                    pred = (Predicate)storeRestrictionList.getOptPredicate(i);
                    if (pred.isInListProbePredicate()) {
                        SanityManager.THROWASSERT("Found IN-list probing " +
                                "predicate (" + pred.binaryRelOpColRefsToString() +
                                ") when no such predicates were expected.");
                    }
                }
            }
        }

        fillInScanArgs2(mb,innerTable, bulkFetch, colRefItem, indexColItem, lockMode, tableLocked, isolationLevel,tableVersion,pin,
                splits, delimited, escaped, lines, storedAs, location, partitionRefItem);
        return numArgs;
    }


    @Override
    public void divideUpPredicateLists(Optimizable innerTable, OptimizablePredicateList originalRestrictionList, OptimizablePredicateList storeRestrictionList, OptimizablePredicateList nonStoreRestrictionList, OptimizablePredicateList requalificationRestrictionList, DataDictionary dd) throws StandardException {
        // originalRestrictionList.setPredicatesAndProperties(storeRestrictionList);
    }

    @Override
    public boolean doesMaterialization() {
        return false;
    }

    /** @see JoinStrategy#multiplyBaseCostByOuterRows */
	public boolean multiplyBaseCostByOuterRows() {
		return true;
	}

    @Override
    public OptimizablePredicateList getBasePredicates(OptimizablePredicateList predList, OptimizablePredicateList basePredicates, Optimizable innerTable) throws StandardException {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(basePredicates.size() == 0,"The base predicate list should be empty.");
        }

        if (predList != null) {
            predList.transferAllPredicates(basePredicates);
            basePredicates.classify(innerTable, innerTable.getCurrentAccessPath().getConglomerateDescriptor());
        }

        /*
         * We want all the join predicates to be included, so we just pass everything through and filter
         * it out through the actual costing algorithm
         */
        return basePredicates;
    }

    @Override
    public double nonBasePredicateSelectivity(Optimizable innerTable, OptimizablePredicateList predList) throws StandardException {
        return 1.0;
    }

    @Override
    public void putBasePredicates(OptimizablePredicateList predList, OptimizablePredicateList basePredicates) throws StandardException {
        for (int i = basePredicates.size() - 1; i >= 0; i--) {
            OptimizablePredicate pred = basePredicates.getOptPredicate(i);
            predList.addOptPredicate(pred);
            basePredicates.removeOptPredicate(i);
        }
    }

    /**
     * 
     * Checks to see if the innerTable is hashable.  If so, it then checks to make sure the
     * data size of the conglomerate (Table or Index) is less than SpliceConstants.broadcastRegionMBThreshold
     * using the HBaseRegionLoads.memstoreAndStorefileSize method on each region load.
     * 
     */
	@Override
	public boolean feasible(Optimizable innerTable,
                            OptimizablePredicateList predList,
                            Optimizer optimizer,
                            CostEstimate outerCost,
                            boolean wasHinted,
                            boolean skipKeyCheck) throws StandardException {
        /* Currently BroadcastJoin does not work with a right side IndexRowToBaseRowOperation */
        return true;
	}

    @Override
    public void estimateCost(Optimizable innerTable,
                             OptimizablePredicateList predList,
                             ConglomerateDescriptor cd,
                             CostEstimate outerCost,
                             Optimizer optimizer,
                             CostEstimate innerCost) throws StandardException{

        if(outerCost.isUninitialized() ||(outerCost.localCost()==0d && outerCost.getEstimatedRowCount()==1.0)){
            /*
             * Derby calls this method at the end of each table scan, even if it's not a join (or if it's
             * the left side of the join). When this happens, the outer cost is still unitialized, so there's
             * nothing to do in this method;
             */
            RowOrdering ro = outerCost.getRowOrdering();
            if(ro!=null)
                outerCost.setRowOrdering(ro); //force a cloning
            return;
        }
        //set the base costs for the join
        innerCost.setBase(innerCost.cloneMe());
        double totalRowCount = outerCost.rowCount()*innerCost.rowCount();

        innerCost.setRowOrdering(outerCost.getRowOrdering());
        innerCost.setEstimatedHeapSize((long) SelectivityUtil.getTotalHeapSize(innerCost, outerCost, totalRowCount));
        innerCost.setNumPartitions(outerCost.partitionCount());
        innerCost.setRowCount(totalRowCount);
        innerCost.setRemoteCost(SelectivityUtil.getTotalRemoteCost(innerCost, outerCost, totalRowCount));
        double joinCost = 100000000.0;
        innerCost.setLocalCost(joinCost);
        innerCost.setLocalCostPerPartition(joinCost);
        innerCost.setSingleScanRowCount(innerCost.getEstimatedRowCount());
    }

    @Override
    public int maxCapacity(int userSpecifiedCapacity, int maxMemoryPerTable, double perRowUsage) {
        return Integer.MAX_VALUE;
    }

    /**
     *
     * Broadcast Join Local Cost Computation
     *
     * Total Cost = (Left Side Cost/Partition Count) + Right Side Cost + Right Side Transfer Cost + Open Cost + Close Cost + 0.1
     *
     * @param innerCost
     * @param outerCost
     * @return
     */
    public static double broadcastJoinStrategyLocalCost(CostEstimate innerCost, CostEstimate outerCost, double numOfJoinedRows) {
        SConfiguration config = EngineDriver.driver().getConfiguration();
        double localLatency = config.getFallbackLocalLatency();
        double joiningRowCost = numOfJoinedRows * localLatency;
        return (outerCost.localCostPerPartition())+innerCost.localCost()+innerCost.remoteCost()+innerCost.getOpenCost()+innerCost.getCloseCost()+.01 // .01 Hash Cost//
               + joiningRowCost/outerCost.partitionCount();
    }

    @Override
    public String toString(){
        return "CrossJoin";
    }

    @Override
    public JoinStrategyType getJoinStrategyType() {
        return JoinStrategyType.CROSS;
    }

    @Override
    public boolean isMemoryUsageUnderLimit(double totalMemoryConsumed) {
        double totalMemoryinMB = totalMemoryConsumed/1024d/1024d;
        SConfiguration configuration=EngineDriver.driver().getConfiguration();
        long regionThreshold = configuration.getBroadcastRegionMbThreshold();

        return (totalMemoryinMB < regionThreshold);
    }

    protected boolean validForOutermostTable() {
        return true;
    }

}

