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

import org.apache.spark.sql.{AnalysisException, SaveMode}
import org.apache.spark.sql.catalyst.analysis.{Catalog, EliminateSubQueries}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Cast}
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.sources.{BaseRelation, HadoopFsRelation, InsertableRelation}

/**
 * A rule to do pre-insert data type casting and field renaming. Before we insert into
 * an [[InsertableRelation]], we will use this rule to make sure that
 * the columns to be inserted have the correct data type and fields have the correct names.
  * 用于执行预插入数据类型转换和字段重命名的规则,
  * 在我们插入[[InsertableRelation]]之前,我们将使用此规则来确保要插入的列具有正确的数据类型,并且字段具有正确的名称
 */
private[sql] object PreInsertCastAndRename extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      // Wait until children are resolved.
      case p: LogicalPlan if !p.childrenResolved => p

      // We are inserting into an InsertableRelation or HadoopFsRelation.
        //我们正在插入InsertableRelation或HadoopFsRelation
      case i @ InsertIntoTable(
      l @ LogicalRelation(_: InsertableRelation | _: HadoopFsRelation, _), _, child, _, _) => {
        // First, make sure the data to be inserted have the same number of fields with the
        // schema of the relation.
        //首先,确保要插入的数据与关系的模式具有相同数量的字段
        if (l.output.size != child.output.size) {
          sys.error(
            s"$l requires that the query in the SELECT clause of the INSERT INTO/OVERWRITE " +
              s"statement generates the same number of columns as its schema.")
        }
        castAndRenameChildOutput(i, l.output, child)
      }
  }

  /** If necessary, cast data types and rename fields to the expected types and names.
    *如有必要,转换数据类型并将字段重命名为预期的类型和名称 */
  def castAndRenameChildOutput(
      insertInto: InsertIntoTable,
      expectedOutput: Seq[Attribute],
      child: LogicalPlan): InsertIntoTable = {
    val newChildOutput = expectedOutput.zip(child.output).map {
      case (expected, actual) =>
        val needCast = !expected.dataType.sameType(actual.dataType)
        // We want to make sure the filed names in the data to be inserted exactly match
        // names in the schema.
        //我们希望确保要插入的数据中的字段名称与模式中的名称完全匹配
        val needRename = expected.name != actual.name
        (needCast, needRename) match {
          case (true, _) => Alias(Cast(actual, expected.dataType), expected.name)()
          case (false, true) => Alias(actual, expected.name)()
          case (_, _) => actual
        }
    }

    if (newChildOutput == child.output) {
      insertInto
    } else {
      insertInto.copy(child = Project(newChildOutput, child))
    }
  }
}

/**
 * A rule to do various checks before inserting into or writing to a data source table.
  * 在插入或写入数据源表之前执行各种检查的规则
 */
private[sql] case class PreWriteCheck(catalog: Catalog) extends (LogicalPlan => Unit) {
  def failAnalysis(msg: String): Unit = { throw new AnalysisException(msg) }

  def apply(plan: LogicalPlan): Unit = {
    plan.foreach {
      case i @ logical.InsertIntoTable(
        l @ LogicalRelation(t: InsertableRelation, _), partition, query, overwrite, ifNotExists) =>
        // Right now, we do not support insert into a data source table with partition specs.
        //现在,我们不支持插入具有分区规范的数据源表
        if (partition.nonEmpty) {
          failAnalysis(s"Insert into a partition is not allowed because $l is not partitioned.")
        } else {
          // Get all input data source relations of the query.
          //获取查询的所有输入数据源关系
          val srcRelations = query.collect {
            case LogicalRelation(src: BaseRelation, _) => src
          }
          if (srcRelations.contains(t)) {
            failAnalysis(
              "Cannot insert overwrite into table that is also being read from.")
          } else {
            // OK
          }
        }

      case logical.InsertIntoTable(
        LogicalRelation(r: HadoopFsRelation, _), part, query, overwrite, _) =>
        // We need to make sure the partition columns specified by users do match partition
        // columns of the relation.
        //我们需要确保用户指定的分区列匹配关系的分区列
        val existingPartitionColumns = r.partitionColumns.fieldNames.toSet
        val specifiedPartitionColumns = part.keySet
        if (existingPartitionColumns != specifiedPartitionColumns) {
          failAnalysis(s"Specified partition columns " +
            s"(${specifiedPartitionColumns.mkString(", ")}) " +
            s"do not match the partition columns of the table. Please use " +
            s"(${existingPartitionColumns.mkString(", ")}) as the partition columns.")
        } else {
          // OK
        }

        // Get all input data source relations of the query.
        //获取查询的所有输入数据源关系
        val srcRelations = query.collect {
          case LogicalRelation(src: BaseRelation, _) => src
        }
        if (srcRelations.contains(r)) {
          failAnalysis(
            "Cannot insert overwrite into table that is also being read from.")
        } else {
          // OK
        }

      case logical.InsertIntoTable(l: LogicalRelation, _, _, _, _) =>
        // The relation in l is not an InsertableRelation.
        failAnalysis(s"$l does not allow insertion.")

      case logical.InsertIntoTable(t, _, _, _, _) =>
        if (!t.isInstanceOf[LeafNode] || t == OneRowRelation || t.isInstanceOf[LocalRelation]) {
          failAnalysis(s"Inserting into an RDD-based table is not allowed.")
        } else {
          // OK
        }

      case CreateTableUsingAsSelect(tableIdent, _, _, _, SaveMode.Overwrite, _, query) =>
        // When the SaveMode is Overwrite, we need to check if the table is an input table of
        // the query. If so, we will throw an AnalysisException to let users know it is not allowed.
        if (catalog.tableExists(tableIdent.toSeq)) {
          // Need to remove SubQuery operator.
          EliminateSubQueries(catalog.lookupRelation(tableIdent.toSeq)) match {
            // Only do the check if the table is a data source table
            // (the relation is a BaseRelation).
            case l @ LogicalRelation(dest: BaseRelation, _) =>
              // Get all input data source relations of the query.
              val srcRelations = query.collect {
                case LogicalRelation(src: BaseRelation, _) => src
              }
              if (srcRelations.contains(dest)) {
                failAnalysis(
                  s"Cannot overwrite table $tableIdent that is also being read from.")
              } else {
                // OK
              }

            case _ => // OK
          }
        } else {
          // OK
        }

      case _ => // OK
    }
  }
}
