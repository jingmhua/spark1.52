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

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SaveMode}

/**
 * Returned for the "DESCRIBE [EXTENDED] [dbName.]tableName" command.
  * 返回“DESCRIBE [EXTENDED] [dbName。] tableName”命令
 * @param table The table to be described.
 * @param isExtended True if "DESCRIBE EXTENDED" is used. Otherwise, false.
 *                   It is effective only when the table is a Hive table.
 */
case class DescribeCommand(
    table: LogicalPlan,
    isExtended: Boolean) extends LogicalPlan with Command {

  override def children: Seq[LogicalPlan] = Seq.empty

  override val output: Seq[Attribute] = Seq(
    // Column names are based on Hive.列名基于Hive
    AttributeReference("col_name", StringType, nullable = false,
      new MetadataBuilder().putString("comment", "name of the column").build())(),
    AttributeReference("data_type", StringType, nullable = false,
      new MetadataBuilder().putString("comment", "data type of the column").build())(),
    AttributeReference("comment", StringType, nullable = false,
      new MetadataBuilder().putString("comment", "comment of the column").build())()
  )
}

/**
  * Used to represent the operation of create table using a data source.
  * 用于表示使用数据源创建表的操作
  * @param allowExisting If it is true, we will do nothing when the table already exists.
  *                      If it is false, an exception will be thrown
  *                      如果确实如此,那么当表已经存在时,我们将不执行任何操作,如果该表为false,则将抛出异常
  */
case class CreateTableUsing(
    tableIdent: TableIdentifier,
    userSpecifiedSchema: Option[StructType],
    provider: String,
    temporary: Boolean,
    options: Map[String, String],
    allowExisting: Boolean,
    managedIfNoPath: Boolean) extends LogicalPlan with Command {

  override def output: Seq[Attribute] = Seq.empty
  override def children: Seq[LogicalPlan] = Seq.empty
}

/**
 * A node used to support CTAS statements and saveAsTable for the data source API.
  * 用于支持CTAS语句的节点和用于数据源API的saveAsTable
 * This node is a [[UnaryNode]] instead of a [[Command]] because we want the analyzer
 * can analyze the logical plan that will be used to populate the table.
 * So, [[PreWriteCheck]] can detect cases that are not allowed.
 */
// TODO: Use TableIdentifier instead of String for tableName (SPARK-10104).
case class CreateTableUsingAsSelect(
    tableIdent: TableIdentifier,
    provider: String,
    temporary: Boolean,
    partitionColumns: Array[String],
    mode: SaveMode,
    options: Map[String, String],
    child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = Seq.empty[Attribute]
}

case class CreateTempTableUsing(
    tableIdent: TableIdentifier,
    userSpecifiedSchema: Option[StructType],
    provider: String,
    options: Map[String, String]) extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val resolved = ResolvedDataSource(
      sqlContext, userSpecifiedSchema, Array.empty[String], provider, options)
    sqlContext.catalog.registerTable(
      tableIdent.toSeq,
      DataFrame(sqlContext, LogicalRelation(resolved.relation)).logicalPlan)

    Seq.empty[Row]
  }
}

case class CreateTempTableUsingAsSelect(
    tableIdent: TableIdentifier,
    provider: String,
    partitionColumns: Array[String],
    mode: SaveMode,
    options: Map[String, String],
    query: LogicalPlan) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val df = DataFrame(sqlContext, query)
    val resolved = ResolvedDataSource(sqlContext, provider, partitionColumns, mode, options, df)
    sqlContext.catalog.registerTable(
      tableIdent.toSeq,
      DataFrame(sqlContext, LogicalRelation(resolved.relation)).logicalPlan)

    Seq.empty[Row]
  }
}

case class RefreshTable(tableIdent: TableIdentifier)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    // Refresh the given table's metadata first.
    //首先刷新给定表的元数据
    sqlContext.catalog.refreshTable(tableIdent)

    // If this table is cached as a InMemoryColumnarRelation, drop the original
    // cached version and make the new version cached lazily.
    //如果此表缓存为InMemoryColumnarRelation,请删除原始缓存版本并使新版本缓存缓存
    val logicalPlan = sqlContext.catalog.lookupRelation(tableIdent.toSeq)
    // Use lookupCachedData directly since RefreshTable also takes databaseName.
    //直接使用lookupCachedData,因为RefreshTable也需要databaseName。
    val isCached = sqlContext.cacheManager.lookupCachedData(logicalPlan).nonEmpty
    if (isCached) {
      // Create a data frame to represent the table.
      //创建表示表的数据框
      // TODO: Use uncacheTable once it supports database name.
      val df = DataFrame(sqlContext, logicalPlan)
      // Uncache the logicalPlan.
      sqlContext.cacheManager.tryUncacheQuery(df, blocking = true)
      // Cache it again.
      sqlContext.cacheManager.cacheQuery(df, Some(tableIdent.table))
    }

    Seq.empty[Row]
  }
}

/**
 * Builds a map in which keys are case insensitive
  * 构建一个映射,其中键不区分大小写
 */
class CaseInsensitiveMap(map: Map[String, String]) extends Map[String, String]
  with Serializable {

  val baseMap = map.map(kv => kv.copy(_1 = kv._1.toLowerCase))

  override def get(k: String): Option[String] = baseMap.get(k.toLowerCase)

  override def + [B1 >: String](kv: (String, B1)): Map[String, B1] =
    baseMap + kv.copy(_1 = kv._1.toLowerCase)

  override def iterator: Iterator[(String, String)] = baseMap.iterator

  override def -(key: String): Map[String, String] = baseMap - key.toLowerCase
}

/**
 * The exception thrown from the DDL parser.
  * 从DDL解析器抛出的异常
 */
class DDLException(message: String) extends RuntimeException(message)
