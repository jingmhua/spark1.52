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

package org.apache.spark.sql.hive.execution

import org.apache.hadoop.hive.serde2.`lazy`.LazySimpleSerDe
import org.scalatest.exceptions.TestFailedException

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.execution.{UnaryNode, SparkPlan, SparkPlanTest}
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.types.StringType
//脚本转换套件
class ScriptTransformationSuite extends SparkPlanTest {

  override def _sqlContext: SQLContext = TestHive
  private val sqlContext = _sqlContext

  private val noSerdeIOSchema = HiveScriptIOSchema(
    inputRowFormat = Seq.empty,
    outputRowFormat = Seq.empty,
    inputSerdeClass = None,
    outputSerdeClass = None,
    inputSerdeProps = Seq.empty,
    outputSerdeProps = Seq.empty,
    recordReaderClass = None,
    recordWriterClass = None,
    schemaLess = false
  )

  private val serdeIOSchema = noSerdeIOSchema.copy(
    inputSerdeClass = Some(classOf[LazySimpleSerDe].getCanonicalName),
    outputSerdeClass = Some(classOf[LazySimpleSerDe].getCanonicalName)
  )
  //
    test("cat without SerDe") {
    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    checkAnswer(
      rowsDf,
      (child: SparkPlan) => new ScriptTransformation(
        input = Seq(rowsDf.col("a").expr),
        script = "cat",
        output = Seq(AttributeReference("a", StringType)()),
        child = child,
        ioschema = noSerdeIOSchema
      )(TestHive),
      rowsDf.collect())
  }

  test("cat with LazySimpleSerDe") {
    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    checkAnswer(
      rowsDf,
      (child: SparkPlan) => new ScriptTransformation(
        input = Seq(rowsDf.col("a").expr),
        script = "cat",
        output = Seq(AttributeReference("a", StringType)()),
        child = child,
        ioschema = serdeIOSchema
      )(TestHive),
      rowsDf.collect())
  }
  //脚本转换不应该从上游运营商（无serde）
  test("script transformation should not swallow errors from upstream operators (no serde)") {
    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    val e = intercept[TestFailedException] {
      checkAnswer(
        rowsDf,
        (child: SparkPlan) => new ScriptTransformation(
          input = Seq(rowsDf.col("a").expr),
          script = "cat",
          output = Seq(AttributeReference("a", StringType)()),
          child = ExceptionInjectingOperator(child),
          ioschema = noSerdeIOSchema
        )(TestHive),
        rowsDf.collect())
    }
    assert(e.getMessage().contains("intentional exception"))
  }
  //脚本转换不应该从上游运营商（使用serde）
  test("script transformation should not swallow errors from upstream operators (with serde)") {
    val rowsDf = Seq("a", "b", "c").map(Tuple1.apply).toDF("a")
    val e = intercept[TestFailedException] {
      checkAnswer(
        rowsDf,
        (child: SparkPlan) => new ScriptTransformation(
          input = Seq(rowsDf.col("a").expr),
          script = "cat",
          output = Seq(AttributeReference("a", StringType)()),
          child = ExceptionInjectingOperator(child),
          ioschema = serdeIOSchema
        )(TestHive),
        rowsDf.collect())
    }
    assert(e.getMessage().contains("intentional exception"))
  }
}

private case class ExceptionInjectingOperator(child: SparkPlan) extends UnaryNode {
  override protected def doExecute(): RDD[InternalRow] = {
    child.execute().map { x =>
      //确保定义了TaskContext
      assert(TaskContext.get() != null) // Make sure that TaskContext is defined.
      //这个睡眠给外部处理开始的时间
      Thread.sleep(1000) // This sleep gives the external process time to start.
      throw new IllegalArgumentException("intentional exception")
    }
  }
  override def output: Seq[Attribute] = child.output
}
