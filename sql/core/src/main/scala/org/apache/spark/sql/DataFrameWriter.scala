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

package org.apache.spark.sql

import java.util.Properties

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.{SqlParser, TableIdentifier}
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.plans.logical.InsertIntoTable
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.execution.datasources.{CreateTableUsingAsSelect, ResolvedDataSource}
import org.apache.spark.sql.sources.HadoopFsRelation


/**
 * :: Experimental ::
 * Interface used to write a [[DataFrame]] to external storage systems (e.g. file systems,
 * key-value stores, etc). Use [[DataFrame.write]] to access this.
  *
  * 用于将[[DataFrame]]写入外部存储系统(例如文件系统,键值存储等)的接口,使用[[DataFrame.write]]访问它。
 *
 * @since 1.4.0
 */
@Experimental
final class DataFrameWriter private[sql](df: DataFrame) {

  /**
   * Specifies the behavior when data or table already exists. Options include:
    * 指定数据或表已存在时的行为,选项包括:
   *   - `SaveMode.Overwrite`: overwrite the existing data.
   *   - `SaveMode.Append`: append the data.
   *   - `SaveMode.Ignore`: ignore the operation (i.e. no-op).
   *   - `SaveMode.ErrorIfExists`: default option, throw an exception at runtime.
   *
   * @since 1.4.0
   */
  def mode(saveMode: SaveMode): DataFrameWriter = {
    this.mode = saveMode
    this
  }

  /**
   * Specifies the behavior when data or table already exists. Options include:
    * 指定数据或表已存在时的行为。 选项包括：
   *   - `overwrite`: overwrite the existing data.覆盖现有数据。
   *   - `append`: append the data.
   *   - `ignore`: ignore the operation (i.e. no-op).忽略操作
   *   - `error`: default option, throw an exception at runtime. 默认选项，在运行时抛出异常
   *
   * @since 1.4.0
   */
  def mode(saveMode: String): DataFrameWriter = {
    this.mode = saveMode.toLowerCase match {
      case "overwrite" => SaveMode.Overwrite
      case "append" => SaveMode.Append
      case "ignore" => SaveMode.Ignore
      case "error" | "default" => SaveMode.ErrorIfExists
      case _ => throw new IllegalArgumentException(s"Unknown save mode: $saveMode. " +
        "Accepted modes are 'overwrite', 'append', 'ignore', 'error'.")
    }
    this
  }

  /**
   * Specifies the underlying output data source. Built-in options include "parquet", "json", etc.
    * 指定基础输出数据源。 内置选项包括“parquet”，“json”等。
   *
   * @since 1.4.0
   */
  def format(source: String): DataFrameWriter = {
    this.source = source
    this
  }

  /**
   * Adds an output option for the underlying data source.
    * 为基础数据源添加输出选项
   *
   * @since 1.4.0
   */
  def option(key: String, value: String): DataFrameWriter = {
    this.extraOptions += (key -> value)
    this
  }

  /**
   * (Scala-specific) Adds output options for the underlying data source.
   *（特定于Scala）添加基础数据源的输出选项
   * @since 1.4.0
   */
  def options(options: scala.collection.Map[String, String]): DataFrameWriter = {
    this.extraOptions ++= options
    this
  }

  /**
   * Adds output options for the underlying data source.
    * 添加基础数据源的输出选项
   *
   * @since 1.4.0
   */
  def options(options: java.util.Map[String, String]): DataFrameWriter = {
    this.options(scala.collection.JavaConversions.mapAsScalaMap(options))
    this
  }

  /**
   * Partitions the output by the given columns on the file system. If specified, the output is
   * laid out on the file system similar to Hive's partitioning scheme.
    *
    * 通过文件系统上的给定列对输出进行分区,如果指定,则输出布局在文件系统上,类似于Hive的分区方案。
   *
   * This is only applicable for Parquet at the moment.
   *
   * @since 1.4.0
   */
  @scala.annotation.varargs
  def partitionBy(colNames: String*): DataFrameWriter = {
    this.partitioningColumns = Option(colNames)
    this
  }

  /**
   * Saves the content of the [[DataFrame]] at the specified path.
    * 将[[DataFrame]]的内容保存在指定的路径中
   *
   * @since 1.4.0
   */
  def save(path: String): Unit = {
    this.extraOptions += ("path" -> path)
    save()
  }

  /**
   * Saves the content of the [[DataFrame]] as the specified table.
    * 将[[DataFrame]]的内容保存为指定的表
   *
   * @since 1.4.0
   */
  def save(): Unit = {
    ResolvedDataSource(
      df.sqlContext,
      source,
      partitioningColumns.map(_.toArray).getOrElse(Array.empty[String]),
      mode,
      extraOptions.toMap,
      df)
  }

  /**
   * Inserts the content of the [[DataFrame]] to the specified table. It requires that
   * the schema of the [[DataFrame]] is the same as the schema of the table.
    *
   *将[[DataFrame]]的内容插入到指定的表中,它要求[[DataFrame]]的模式与表的模式相同
    *
   * Because it inserts data to an existing table, format or options will be ignored.
   *
   * @since 1.4.0
   */
  def insertInto(tableName: String): Unit = {
    insertInto(SqlParser.parseTableIdentifier(tableName))
  }

  private def insertInto(tableIdent: TableIdentifier): Unit = {
    val partitions = partitioningColumns.map(_.map(col => col -> (None: Option[String])).toMap)
    val overwrite = mode == SaveMode.Overwrite
    df.sqlContext.executePlan(
      InsertIntoTable(
        UnresolvedRelation(tableIdent.toSeq),
        partitions.getOrElse(Map.empty[String, Option[String]]),
        df.logicalPlan,
        overwrite,
        ifNotExists = false)).toRdd
  }

  /**
   * Saves the content of the [[DataFrame]] as the specified table.
    * 将[[DataFrame]]的内容保存为指定的表
   *
   * In the case the table already exists, behavior of this function depends on the
   * save mode, specified by the `mode` function (default to throwing an exception).

    * 在表已经存在的情况下，此函数的行为取决于由`mode`函数指定的save模式（默认为抛出异常）
    *
   * When `mode` is `Overwrite`, the schema of the [[DataFrame]] does not need to be
   * the same as that of the existing table.
   * When `mode` is `Append`, the schema of the [[DataFrame]] need to be
   * the same as that of the existing table, and format or options will be ignored.
   *
   * When the DataFrame is created from a non-partitioned [[HadoopFsRelation]] with a single input
   * path, and the data source provider can be mapped to an existing Hive builtin SerDe (i.e. ORC
   * and Parquet), the table is persisted in a Hive compatible format, which means other systems
   * like Hive will be able to read this table. Otherwise, the table is persisted in a Spark SQL
   * specific format.
   *
   * @since 1.4.0
   */
  def saveAsTable(tableName: String): Unit = {
    saveAsTable(SqlParser.parseTableIdentifier(tableName))
  }

  private def saveAsTable(tableIdent: TableIdentifier): Unit = {
    val tableExists = df.sqlContext.catalog.tableExists(tableIdent.toSeq)

    (tableExists, mode) match {
      case (true, SaveMode.Ignore) =>
        // Do nothing

      case (true, SaveMode.ErrorIfExists) =>
        throw new AnalysisException(s"Table $tableIdent already exists.")

      case (true, SaveMode.Append) =>
        // If it is Append, we just ask insertInto to handle it. We will not use insertInto
        // to handle saveAsTable with Overwrite because saveAsTable can change the schema of
        // the table. But, insertInto with Overwrite requires the schema of data be the same
        // the schema of the table.
        insertInto(tableIdent)

      case _ =>
        val cmd =
          CreateTableUsingAsSelect(
            tableIdent,
            source,
            temporary = false,
            partitioningColumns.map(_.toArray).getOrElse(Array.empty[String]),
            mode,
            extraOptions.toMap,
            df.logicalPlan)
        df.sqlContext.executePlan(cmd).toRdd
    }
  }

  /**
   * Saves the content of the [[DataFrame]] to a external database table via JDBC. In the case the
   * table already exists in the external database, behavior of this function depends on the
   * save mode, specified by the `mode` function (default to throwing an exception).
    *
    * 通过JDBC将[[DataFrame]]的内容保存到外部数据库表,如果表已存在于外部数据库中,
    * 则此函数的行为取决于由“mode”函数指定的save模式(默认为抛出异常)
   *
   * Don't create too many partitions in parallel on a large cluster; otherwise Spark might crash
   * your external database systems.
   *
   * @param url JDBC database url of the form `jdbc:subprotocol:subname`
   * @param table Name of the table in the external database.
   * @param connectionProperties JDBC database connection arguments, a list of arbitrary string
   *                             tag/value. Normally at least a "user" and "password" property
   *                             should be included.
   */
  def jdbc(url: String, table: String, connectionProperties: Properties): Unit = {
    val props = new Properties()
    extraOptions.foreach { case (key, value) =>
      props.put(key, value)
    }
    // connectionProperties should override settings in extraOptions
    //connectionProperties应覆盖extraOptions中的设置
    props.putAll(connectionProperties)
    val conn = JdbcUtils.createConnection(url, props)

    try {
      var tableExists = JdbcUtils.tableExists(conn, table)

      if (mode == SaveMode.Ignore && tableExists) {
        return
      }

      if (mode == SaveMode.ErrorIfExists && tableExists) {
        sys.error(s"Table $table already exists.")
      }

      if (mode == SaveMode.Overwrite && tableExists) {
        JdbcUtils.dropTable(conn, table)
        tableExists = false
      }

      // Create the table if the table didn't exist.
      if (!tableExists) {
        val schema = JdbcUtils.schemaString(df, url)
        val sql = s"CREATE TABLE $table ($schema)"
        conn.prepareStatement(sql).executeUpdate()
      }
    } finally {
      conn.close()
    }

    JdbcUtils.saveTable(df, url, table, props)
  }

  /**
   * Saves the content of the [[DataFrame]] in JSON format at the specified path.
    * 以指定路径保存JSON格式的[[DataFrame]]内容
   * This is equivalent to:
   * {{{
   *   format("json").save(path)
   * }}}
   *
   * @since 1.4.0
   */
  def json(path: String): Unit = format("json").save(path)

  /**
   * Saves the content of the [[DataFrame]] in Parquet format at the specified path.
    * 以指定路径保存Parquet格式的[[DataFrame]]内容
   * This is equivalent to:
   * {{{
   *   format("parquet").save(path)
   * }}}
   *
   * @since 1.4.0
   */
  def parquet(path: String): Unit = format("parquet").save(path)

  /**
   * Saves the content of the [[DataFrame]] in ORC format at the specified path.
    * 以指定路径的ORC格式保存[[DataFrame]]的内容
   * This is equivalent to:
   * {{{
   *   format("orc").save(path)
   * }}}
   *
   * @since 1.5.0
   * @note Currently, this method can only be used together with `HiveContext`.
   */
  def orc(path: String): Unit = format("orc").save(path)

  ///////////////////////////////////////////////////////////////////////////////////////
  // Builder pattern config options
  ///////////////////////////////////////////////////////////////////////////////////////

  private var source: String = df.sqlContext.conf.defaultDataSourceName

  private var mode: SaveMode = SaveMode.ErrorIfExists

  private var extraOptions = new scala.collection.mutable.HashMap[String, String]

  private var partitioningColumns: Option[Seq[String]] = None

}
