/*
 *
 *  * Copyright (c) 2012 - 2019 Splice Machine, Inc.
 *  *
 *  * This file is part of Splice Machine.
 *  * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 *  * GNU Affero General Public License as published by the Free Software Foundation, either
 *  * version 3, or (at your option) any later version.
 *  * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  * See the GNU Affero General Public License for more details.
 *  * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 *  * If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package com.splicemachine.storage;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.derby.hbase.AllocatedFilter;
import com.splicemachine.si.api.server.ClusterHealth;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.io.IOException;

/**
 * @author Scott Fines
 *         Date: 12/18/15
 */
public class HClusterHealthFactory implements ClusterHealth {

    private final RecoverableZooKeeper rzk;

    public HClusterHealthFactory(RecoverableZooKeeper rzk){
        this.rzk = rzk;
    }

    @Override
    public ClusterHealthWatcher registerWatcher() {
        HClusterHealthWatcher hw = new HClusterHealthWatcher(rzk);
        hw.register();
        return hw;
    }

    private class HClusterHealthWatcher implements ClusterHealthWatcher, Watcher {

        private final RecoverableZooKeeper rzk;
        private int failures = 0;

        HClusterHealthWatcher(RecoverableZooKeeper rzk) {
            this.rzk = rzk;
        }

        @Override
        public synchronized int failedServers() {
            return failures;
        }

        @Override
        public void close() {
            //nothing
        }

        @Override
        public synchronized void process(WatchedEvent watchedEvent) {
            if (watchedEvent.getType() == Event.EventType.NodeChildrenChanged) {
                failures++;
            }
            register();
        }

        void register() {
            try {
                rzk.getChildren("/hbase/rs", this);
            } catch (Exception e) {
                throw new RuntimeException("Couldn't register cluster health watcher", e);
            }
        }
    }
}
