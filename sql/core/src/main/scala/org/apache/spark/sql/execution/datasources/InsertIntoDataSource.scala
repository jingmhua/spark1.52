/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.sources.InsertableRelation


/**
 * Inserts the results of `query` in to a relation that extends [[InsertableRelation]].
  * 将`query`的结果插入到扩展[[InsertableRelation]]的关系中
 */
private[sql] case class InsertIntoDataSource(
    logicalRelation: LogicalRelation,
    query: LogicalPlan,
    overwrite: Boolean)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val relation = logicalRelation.relation.asInstanceOf[InsertableRelation]
    val data = DataFrame(sqlContext, query)
    // Apply the schema of the existing table to the new data.
    //将现有表的架构应用于新数据
    val df = sqlContext.internalCreateDataFrame(data.queryExecution.toRdd, logicalRelation.schema)
    relation.insert(df, overwrite)

    // Invalidate the cache.
    //使缓存无效
    sqlContext.cacheManager.invalidateCache(logicalRelation)

    Seq.empty[Row]
  }
}
