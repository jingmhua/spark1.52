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

package org.apache.spark.sql.execution.joins

import scala.concurrent._
import scala.concurrent.duration._

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.plans.physical.{Distribution, Partitioning, UnspecifiedDistribution}
import org.apache.spark.sql.execution.{BinaryNode, SQLExecution, SparkPlan}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.util.ThreadUtils
import org.apache.spark.{InternalAccumulator, TaskContext}

/**
 * :: DeveloperApi ::
 * Performs an inner hash join of two child relations.  When the output RDD of this operator is
 * being constructed, a Spark job is asynchronously started to calculate the values for the
 * broadcasted relation.  This data is then placed in a Spark broadcast variable.  The streamed
 * relation is not shuffled.
  * 执行两个子关系的内部哈希联接,当构造此运算符的输出RDD时,异步启动Spark作业以计算广播关系的值,
  * 然后将此数据放在Spark广播变量中,流式关系不会被洗牌。
  *
 */
@DeveloperApi
case class BroadcastHashJoin(
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    buildSide: BuildSide,
    left: SparkPlan,
    right: SparkPlan)
  extends BinaryNode with HashJoin {

  override private[sql] lazy val metrics = Map(
    "numLeftRows" -> SQLMetrics.createLongMetric(sparkContext, "number of left rows"),
    "numRightRows" -> SQLMetrics.createLongMetric(sparkContext, "number of right rows"),
    "numOutputRows" -> SQLMetrics.createLongMetric(sparkContext, "number of output rows"))

  val timeout: Duration = {
    val timeoutValue = sqlContext.conf.broadcastTimeout
    if (timeoutValue < 0) {
      Duration.Inf
    } else {
      timeoutValue.seconds
    }
  }

  override def outputPartitioning: Partitioning = streamedPlan.outputPartitioning

  override def requiredChildDistribution: Seq[Distribution] =
    UnspecifiedDistribution :: UnspecifiedDistribution :: Nil

  // Use lazy so that we won't do broadcast when calling explain but still cache the broadcast value
  // for the same query.
  //使用延迟使我们在调用explain时不会进行广播,但仍然缓存同一查询的广播值。
  @transient
  private lazy val broadcastFuture = {
    val numBuildRows = buildSide match {
      case BuildLeft => longMetric("numLeftRows")
      case BuildRight => longMetric("numRightRows")
    }

    // broadcastFuture is used in "doExecute". Therefore we can get the execution id correctly here.
    //broadcastFuture用于“doExecute”, 因此我们可以在这里正确获取执行ID
    val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    future {
      // This will run in another thread. Set the execution id so that we can connect these jobs
      // with the correct execution.
      //这将在另一个线程中运行,设置执行ID,以便我们可以通过正确的执行来连接这些作业。
      SQLExecution.withExecutionId(sparkContext, executionId) {
        // Note that we use .execute().collect() because we don't want to convert data to Scala
        // types
        //请注意,我们使用.execute（）。collect（）,因为我们不想将数据转换为Scala类型
        val input: Array[InternalRow] = buildPlan.execute().map { row =>
          numBuildRows += 1
          row.copy()
        }.collect()
        // The following line doesn't run in a job so we cannot track the metric value. However, we
        // have already tracked it in the above lines. So here we can use
        // `SQLMetrics.nullLongMetric` to ignore it.
        //以下行不在作业中运行,因此我们无法跟踪度量标准值,但是,我们已经在上述内容中对其进行了跟踪,
        //所以在这里我们可以使用`SQLMetrics.nullLongMetric`来忽略它。
        val hashed = HashedRelation(
          input.iterator, SQLMetrics.nullLongMetric, buildSideKeyGenerator, input.size)
        sparkContext.broadcast(hashed)
      }
    }(BroadcastHashJoin.broadcastHashJoinExecutionContext)
  }

  protected override def doPrepare(): Unit = {
    broadcastFuture
  }

  protected override def doExecute(): RDD[InternalRow] = {
    val numStreamedRows = buildSide match {
      case BuildLeft => longMetric("numRightRows")
      case BuildRight => longMetric("numLeftRows")
    }
    val numOutputRows = longMetric("numOutputRows")

    val broadcastRelation = Await.result(broadcastFuture, timeout)

    streamedPlan.execute().mapPartitions { streamedIter =>
      val hashedRelation = broadcastRelation.value
      hashedRelation match {
        case unsafe: UnsafeHashedRelation =>
          TaskContext.get().internalMetricsToAccumulators(
            InternalAccumulator.PEAK_EXECUTION_MEMORY).add(unsafe.getUnsafeSize)
        case _ =>
      }
      hashJoin(streamedIter, numStreamedRows, hashedRelation, numOutputRows)
    }
  }
}

object BroadcastHashJoin {

  private[joins] val broadcastHashJoinExecutionContext = ExecutionContext.fromExecutorService(
    ThreadUtils.newDaemonCachedThreadPool("broadcast-hash-join", 128))
}
