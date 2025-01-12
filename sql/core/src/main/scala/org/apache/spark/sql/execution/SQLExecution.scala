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

package org.apache.spark.sql.execution

import java.util.concurrent.atomic.AtomicLong

import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.execution.ui.SparkPlanGraph
import org.apache.spark.util.Utils

private[sql] object SQLExecution {

  val EXECUTION_ID_KEY = "spark.sql.execution.id"

  private val _nextExecutionId = new AtomicLong(0)

  private def nextExecutionId: Long = _nextExecutionId.getAndIncrement

  /**
   * Wrap an action that will execute "queryExecution" to track all Spark jobs in the body so that
   * we can connect them with an execution.
    * 包装将执行“queryExecution”的操作以跟踪正文中的所有Spark作业,以便我们可以将它们与执行连接起来
   */
  def withNewExecutionId[T](
      sqlContext: SQLContext, queryExecution: SQLContext#QueryExecution)(body: => T): T = {
    val sc = sqlContext.sparkContext
    val oldExecutionId = sc.getLocalProperty(EXECUTION_ID_KEY)
    if (oldExecutionId == null) {
      val executionId = SQLExecution.nextExecutionId
      sc.setLocalProperty(EXECUTION_ID_KEY, executionId.toString)
      val r = try {
        val callSite = Utils.getCallSite()
        sqlContext.listener.onExecutionStart(
          executionId,
          callSite.shortForm,
          callSite.longForm,
          queryExecution.toString,
          SparkPlanGraph(queryExecution.executedPlan),
          System.currentTimeMillis())
        try {
          body
        } finally {
          // Ideally, we need to make sure onExecutionEnd happens after onJobStart and onJobEnd.
          // However, onJobStart and onJobEnd run in the listener thread. Because we cannot add new
          // SQL event types to SparkListener since it's a public API, we cannot guarantee that.
          //理想情况下，我们需要确保在onJobStart和onJobEnd之后发生onExecutionEnd。
          //但是,onJobStart和onJobEnd在侦听器线程中运行,
          // 因为我们无法向SparkListener添加新的SQL事件类型,因为它是一个公共API,我们无法保证

          // SQLListener should handle the case that onExecutionEnd happens before onJobEnd.
          //
          // The worst case is onExecutionEnd may happen before onJobStart when the listener thread
          // is very busy. If so, we cannot track the jobs for the execution. It seems acceptable.
          //最糟糕的情况是onExecutionEnd可能在onJobStart之前发生,当侦听器线程非常繁忙时。
          //如果是这样,我们无法跟踪执行的作业,这似乎可以接受。
          sqlContext.listener.onExecutionEnd(executionId, System.currentTimeMillis())
        }
      } finally {
        sc.setLocalProperty(EXECUTION_ID_KEY, null)
      }
      r
    } else {
      // Don't support nested `withNewExecutionId`. This is an example of the nested
      // `withNewExecutionId`:
      //
      // class DataFrame {
      //   def foo: T = withNewExecutionId { something.createNewDataFrame().collect() }
      // }
      //
      // Note: `collect` will call withNewExecutionId
      // In this case, only the "executedPlan" for "collect" will be executed. The "executedPlan"
      // for the outer DataFrame won't be executed. So it's meaningless to create a new Execution
      // for the outer DataFrame. Even if we track it, since its "executedPlan" doesn't run,
      // all accumulator metrics will be 0. It will confuse people if we show them in Web UI.
      //
      // A real case is the `DataFrame.count` method.
      throw new IllegalArgumentException(s"$EXECUTION_ID_KEY is already set")
    }
  }

  /**
   * Wrap an action with a known executionId. When running a different action in a different
   * thread from the original one, this method can be used to connect the Spark jobs in this action
   * with the known executionId, e.g., `BroadcastHashJoin.broadcastFuture`.
    * 使用已知的executionId包装动作,当在与原始线程不同的线程中运行不同的操作时,
    * 此方法可用于将此操作中的Spark作业与已知的executionId连接,例如，“BroadcastHashJoin.broadcastFuture”
   */
  def withExecutionId[T](sc: SparkContext, executionId: String)(body: => T): T = {
    val oldExecutionId = sc.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    try {
      sc.setLocalProperty(SQLExecution.EXECUTION_ID_KEY, executionId)
      body
    } finally {
      sc.setLocalProperty(SQLExecution.EXECUTION_ID_KEY, oldExecutionId)
    }
  }
}
