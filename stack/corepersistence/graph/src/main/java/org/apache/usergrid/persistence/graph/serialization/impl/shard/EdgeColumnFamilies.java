/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */
package org.apache.usergrid.persistence.graph.serialization.impl.shard;


import org.apache.usergrid.persistence.core.astyanax.MultiTennantColumnFamily;
import org.apache.usergrid.persistence.core.astyanax.ScopedRowKey;
import org.apache.usergrid.persistence.core.migration.schema.Migration;


/**
 * Implementation for using multiple column families
 */
public interface EdgeColumnFamilies extends Migration{

    /**
     * Get the name of the column family for getting source nodes
     */
    public MultiTennantColumnFamily<ScopedRowKey<RowKey>, DirectedEdge> getSourceNodeCfName();

    /**
     * Get the name of the column family for getting target nodes
     */
    public MultiTennantColumnFamily<ScopedRowKey<RowKey>, DirectedEdge> getTargetNodeCfName();


    /**
     * Get the name of the column family for getting source nodes  with a target type
     */
    public MultiTennantColumnFamily<ScopedRowKey<RowKeyType>, DirectedEdge> getSourceNodeTargetTypeCfName();

    /**
     * Get the name of the column family for getting target nodes with a source type
     */
    public MultiTennantColumnFamily<ScopedRowKey<RowKeyType>, DirectedEdge> getTargetNodeSourceTypeCfName();

    /**
     * Get the Graph edge versions cf
     * @return
     */
    public MultiTennantColumnFamily<ScopedRowKey<EdgeRowKey>, Long> getGraphEdgeVersions();
}
