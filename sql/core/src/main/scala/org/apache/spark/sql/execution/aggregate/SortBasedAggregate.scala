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

package org.apache.spark.sql.execution.aggregate

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.physical.{UnspecifiedDistribution, ClusteredDistribution, AllTuples, Distribution}
import org.apache.spark.sql.execution.{UnsafeFixedWidthAggregationMap, SparkPlan, UnaryNode}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.types.StructType

case class SortBasedAggregate(
    requiredChildDistributionExpressions: Option[Seq[Expression]],
    groupingExpressions: Seq[NamedExpression],
    nonCompleteAggregateExpressions: Seq[AggregateExpression2],
    nonCompleteAggregateAttributes: Seq[Attribute],
    completeAggregateExpressions: Seq[AggregateExpression2],
    completeAggregateAttributes: Seq[Attribute],
    initialInputBufferOffset: Int,
    resultExpressions: Seq[NamedExpression],
    child: SparkPlan)
  extends UnaryNode {

  override private[sql] lazy val metrics = Map(
    "numInputRows" -> SQLMetrics.createLongMetric(sparkContext, "number of input rows"),
    "numOutputRows" -> SQLMetrics.createLongMetric(sparkContext, "number of output rows"))

  override def outputsUnsafeRows: Boolean = false

  override def canProcessUnsafeRows: Boolean = false

  override def canProcessSafeRows: Boolean = true

  override def output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

  override def requiredChildDistribution: List[Distribution] = {
    requiredChildDistributionExpressions match {
      //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
      case Some(exprs) if exprs.length == 0 => AllTuples :: Nil
      //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
      case Some(exprs) if exprs.length > 0 => ClusteredDistribution(exprs) :: Nil
      //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
      case None => UnspecifiedDistribution :: Nil
    }
  }

  override def requiredChildOrdering: Seq[Seq[SortOrder]] = {
    //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
    groupingExpressions.map(SortOrder(_, Ascending)) :: Nil
  }

  override def outputOrdering: Seq[SortOrder] = {
    groupingExpressions.map(SortOrder(_, Ascending))
  }

  protected override def doExecute(): RDD[InternalRow] = attachTree(this, "execute") {
    val numInputRows = longMetric("numInputRows")
    val numOutputRows = longMetric("numOutputRows")
    child.execute().mapPartitions { iter =>
      // Because the constructor of an aggregation iterator will read at least the first row,
      // we need to get the value of iter.hasNext first.
      val hasInput = iter.hasNext
      if (!hasInput && groupingExpressions.nonEmpty) {
        // This is a grouped aggregate and the input iterator is empty,
        // so return an empty iterator.
        Iterator[InternalRow]()
      } else {
        val outputIter = SortBasedAggregationIterator.createFromInputIterator(
          groupingExpressions,
          nonCompleteAggregateExpressions,
          nonCompleteAggregateAttributes,
          completeAggregateExpressions,
          completeAggregateAttributes,
          initialInputBufferOffset,
          resultExpressions,
          newMutableProjection _,
          newProjection _,
          child.output,
          iter,
          outputsUnsafeRows,
          numInputRows,
          numOutputRows)
        if (!hasInput && groupingExpressions.isEmpty) {
          // There is no input and there is no grouping expressions.
          // We need to output a single row as the output.
          numOutputRows += 1
          Iterator[InternalRow](outputIter.outputForEmptyGroupingKeyWithoutInput())
        } else {
          outputIter
        }
      }
    }
  }

  override def simpleString: String = {
    val allAggregateExpressions = nonCompleteAggregateExpressions ++ completeAggregateExpressions

    val keyString = groupingExpressions.mkString("[", ",", "]")
    val functionString = allAggregateExpressions.mkString("[", ",", "]")
    val outputString = output.mkString("[", ",", "]")
    s"SortBasedAggregate(key=$keyString, functions=$functionString, output=$outputString)"
  }
}
