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

import org.scalatest.BeforeAndAfterAll
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.execution.aggregate
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.expressions.{MutableAggregationBuffer, UserDefinedAggregateFunction}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types._
import org.apache.spark.sql.hive.aggregate.{MyDoubleAvg, MyDoubleSum}
//合计函数
class ScalaAggregateFunction(schema: StructType) extends UserDefinedAggregateFunction {

  def inputSchema: StructType = schema

  def bufferSchema: StructType = schema

  def dataType: DataType = schema

  def deterministic: Boolean = true

  def initialize(buffer: MutableAggregationBuffer): Unit = {
    (0 until schema.length).foreach { i =>
      buffer.update(i, null)
    }
  }

  def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
    if (!input.isNullAt(0) && input.getInt(0) == 50) {
      (0 until schema.length).foreach { i =>
        buffer.update(i, input.get(i))
      }
    }
  }

  def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
    if (!buffer2.isNullAt(0) && buffer2.getInt(0) == 50) {
      (0 until schema.length).foreach { i =>
        buffer1.update(i, buffer2.get(i))
      }
    }
  }

  def evaluate(buffer: Row): Any = {
    Row.fromSeq(buffer.toSeq)
  }
}
//聚集含数查询
abstract class AggregationQuerySuite extends QueryTest with SQLTestUtils with BeforeAndAfterAll {
  override def _sqlContext: SQLContext = TestHive
  protected val sqlContext = _sqlContext
  import sqlContext.implicits._

  var originalUseAggregate2: Boolean = _

  override def beforeAll(): Unit = {
    originalUseAggregate2 = sqlContext.conf.useSqlAggregate2
    sqlContext.setConf(SQLConf.USE_SQL_AGGREGATE2.key, "true")
    val data1 = Seq[(Integer, Integer)](
      (1, 10),
      (null, -60),
      (1, 20),
      (1, 30),
      (2, 0),
      (null, -10),
      (2, -1),
      (2, null),
      (2, null),
      (null, 100),
      (3, null),
      (null, null),
      (3, null)).toDF("key", "value")
    data1.write.saveAsTable("agg1")

    val data2 = Seq[(Integer, Integer, Integer)](
      (1, 10, -10),
      (null, -60, 60),
      (1, 30, -30),
      (1, 30, 30),
      (2, 1, 1),
      (null, -10, 10),
      (2, -1, null),
      (2, 1, 1),
      (2, null, 1),
      (null, 100, -10),
      (3, null, 3),
      (null, null, null),
      (3, null, null)).toDF("key", "value1", "value2")
    data2.write.saveAsTable("agg2")

    val emptyDF = sqlContext.createDataFrame(
      sqlContext.sparkContext.emptyRDD[Row],
      StructType(StructField("key", StringType) :: StructField("value", IntegerType) :: Nil))
    emptyDF.registerTempTable("emptyTable")

    // Register UDAFs
    sqlContext.udf.register("mydoublesum", new MyDoubleSum)
    sqlContext.udf.register("mydoubleavg", new MyDoubleAvg)
  }

  override def afterAll(): Unit = {
    sqlContext.sql("DROP TABLE IF EXISTS agg1")
    sqlContext.sql("DROP TABLE IF EXISTS agg2")
    sqlContext.dropTempTable("emptyTable")
    sqlContext.setConf(SQLConf.USE_SQL_AGGREGATE2.key, originalUseAggregate2.toString)
  }

  test("empty table") {
    // If there is no GROUP BY clause and the table is empty, we will generate a single row.
    //  如果没有按组子句和表是空的，我们将生成一行
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  AVG(value),
          |  COUNT(*),
          |  COUNT(key),
          |  COUNT(value),
          |  FIRST(key),
          |  LAST(value),
          |  MAX(key),
          |  MIN(value),
          |  SUM(key)
          |FROM emptyTable
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(null, 0, 0, 0, null, null, null, null, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  AVG(value),
          |  COUNT(*),
          |  COUNT(key),
          |  COUNT(value),
          |  FIRST(key),
          |  LAST(value),
          |  MAX(key),
          |  MIN(value),
          |  SUM(key),
          |  COUNT(DISTINCT value)
          |FROM emptyTable
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(null, 0, 0, 0, null, null, null, null, null, 0) :: Nil)

    // If there is a GROUP BY clause and the table is empty, there is no output.
    // 如果有一个组BY子句,表是空的,则没有输出
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  AVG(value),
          |  COUNT(*),
          |  COUNT(value),
          |  FIRST(value),
          |  LAST(value),
          |  MAX(value),
          |  MIN(value),
          |  SUM(value),
          |  COUNT(DISTINCT value)
          |FROM emptyTable
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Nil)
  }
  //null 文字
  test("null literal") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  AVG(null),
          |  COUNT(null),
          |  FIRST(null),
          |  LAST(null),
          |  MAX(null),
          |  MIN(null),
          |  SUM(null)
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(null, 0, null, null, null, null, null) :: Nil)
  }
  //只做分组
  test("only do grouping") {

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT key
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1) :: Row(2) :: Row(3) :: Row(null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT DISTINCT value1, key
          |FROM agg2
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(10, 1) ::
        Row(-60, null) ::
        Row(30, 1) ::
        Row(1, 2) ::
        Row(-10, null) ::
        Row(-1, 2) ::
        Row(null, 2) ::
        Row(100, null) ::
        Row(null, 3) ::
        Row(null, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT value1, key
          |FROM agg2
          |GROUP BY key, value1
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(10, 1) ::
        Row(-60, null) ::
        Row(30, 1) ::
        Row(1, 2) ::
        Row(-10, null) ::
        Row(-1, 2) ::
        Row(null, 2) ::
        Row(100, null) ::
        Row(null, 3) ::
        Row(null, null) :: Nil)
  }
  //大小写敏感的解析
  ignore("case in-sensitive resolution") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT avg(value), kEY - 100
          |FROM agg1
          |GROUP BY Key - 100
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(20.0, -99) :: Row(-0.5, -98) :: Row(null, -97) :: Row(10.0, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT sum(distinct value1), kEY - 100, count(distinct value1)
          |FROM agg2
          |GROUP BY Key - 100
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(40, -99, 2) :: Row(0, -98, 2) :: Row(null, -97, 0) :: Row(30, null, 3) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT valUe * key - 100
          |FROM agg1
          |GROUP BY vAlue * keY - 100
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(-90) ::
        Row(-80) ::
        Row(-70) ::
        Row(-100) ::
        Row(-102) ::
        Row(null) :: Nil)
  }
  //测试平均没有key输出
  ignore("test average no key in output") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(-0.5) :: Row(20.0) :: Row(null) :: Row(10.0) :: Nil)
  }
  //测试平均值
  ignore("test average") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT key, avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1, 20.0) :: Row(2, -0.5) :: Row(3, null) :: Row(null, 10.0) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT avg(value), key
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(20.0, 1) :: Row(-0.5, 2) :: Row(null, 3) :: Row(10.0, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT avg(value) + 1.5, key + 10
          |FROM agg1
          |GROUP BY key + 10
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(21.5, 11) :: Row(1.0, 12) :: Row(null, 13) :: Row(11.5, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT avg(value) FROM agg1
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(11.125) :: Nil)
  }
  //自定义函数
  ignore("udaf") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  key,
          |  mydoublesum(value + 1.5 * key),
          |  mydoubleavg(value),
          |  avg(value - key),
          |  mydoublesum(value - 1.5 * key),
          |  avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1, 64.5, 120.0, 19.0, 55.5, 20.0) ::
        Row(2, 5.0, 99.5, -2.5, -7.0, -0.5) ::
        Row(3, null, null, null, null, null) ::
        Row(null, null, 110.0, null, null, 10.0) :: Nil)
  }
  //非代数聚合函数
  ignore("non-AlgebraicAggregate aggreguate function") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT mydoublesum(value), key
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(60.0, 1) :: Row(-1.0, 2) :: Row(null, 3) :: Row(30.0, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT mydoublesum(value) FROM agg1
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(89.0) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT mydoublesum(null)
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(null) :: Nil)
  }
  //非代数聚合和代数聚合函数
  ignore("non-AlgebraicAggregate and AlgebraicAggregate aggreguate function") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT mydoublesum(value), key, avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(60.0, 1, 20.0) ::
        Row(-1.0, 2, -0.5) ::
        Row(null, 3, null) ::
        Row(30.0, null, 10.0) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  mydoublesum(value + 1.5 * key),
          |  avg(value - key),
          |  key,
          |  mydoublesum(value - 1.5 * key),
          |  avg(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(64.5, 19.0, 1, 55.5, 20.0) ::
        Row(5.0, -2.5, 2, -7.0, -0.5) ::
        Row(null, null, 3, null, null) ::
        Row(null, null, null, null, 10.0) :: Nil)
  }
  //单独的列集
  ignore("single distinct column set") {
    // DISTINCT is not meaningful with Max and Min, so we just ignore the DISTINCT keyword.
    //对于Max和Min,DISTINCT无意义,所以我们忽略DISTINCT关键字
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  min(distinct value1),
          |  sum(distinct value1),
          |  avg(value1),
          |  avg(value2),
          |  max(distinct value1)
          |FROM agg2
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(-60, 70.0, 101.0/9.0, 5.6, 100))

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  mydoubleavg(distinct value1),
          |  avg(value1),
          |  avg(value2),
          |  key,
          |  mydoubleavg(value1 - 1),
          |  mydoubleavg(distinct value1) * 0.1,
          |  avg(value1 + value2)
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(120.0, 70.0/3.0, -10.0/3.0, 1, 67.0/3.0 + 100.0, 12.0, 20.0) ::
        Row(100.0, 1.0/3.0, 1.0, 2, -2.0/3.0 + 100.0, 10.0, 2.0) ::
        Row(null, null, 3.0, 3, null, null, null) ::
        Row(110.0, 10.0, 20.0, null, 109.0, 11.0, 30.0) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  key,
          |  mydoubleavg(distinct value1),
          |  mydoublesum(value2),
          |  mydoublesum(distinct value1),
          |  mydoubleavg(distinct value1),
          |  mydoubleavg(value1)
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1, 120.0, -10.0, 40.0, 120.0, 70.0/3.0 + 100.0) ::
        Row(2, 100.0, 3.0, 0.0, 100.0, 1.0/3.0 + 100.0) ::
        Row(3, null, 3.0, null, null, null) ::
        Row(null, 110.0, 60.0, 30.0, 110.0, 110.0) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  count(value1),
          |  count(*),
          |  count(1),
          |  count(DISTINCT value1),
          |  key
          |FROM agg2
          |GROUP BY key
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(3, 3, 3, 2, 1) ::
        Row(3, 4, 4, 2, 2) ::
        Row(0, 2, 2, 0, 3) ::
        Row(3, 4, 4, 3, null) :: Nil)
  }
  //测试计数
  ignore("test count") {
    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  count(value2),
          |  value1,
          |  count(*),
          |  count(1),
          |  key
          |FROM agg2
          |GROUP BY key, value1
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1, 10, 1, 1, 1) ::
        Row(1, -60, 1, 1, null) ::
        Row(2, 30, 2, 2, 1) ::
        Row(2, 1, 2, 2, 2) ::
        Row(1, -10, 1, 1, null) ::
        Row(0, -1, 1, 1, 2) ::
        Row(1, null, 1, 1, 2) ::
        Row(1, 100, 1, 1, null) ::
        Row(1, null, 2, 2, 3) ::
        Row(0, null, 1, 1, null) :: Nil)

    checkAnswer(
      sqlContext.sql(
        """
          |SELECT
          |  count(value2),
          |  value1,
          |  count(*),
          |  count(1),
          |  key,
          |  count(DISTINCT abs(value2))
          |FROM agg2
          |GROUP BY key, value1
        """.stripMargin),
      //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      Row(1, 10, 1, 1, 1, 1) ::
        Row(1, -60, 1, 1, null, 1) ::
        Row(2, 30, 2, 2, 1, 1) ::
        Row(2, 1, 2, 2, 2, 1) ::
        Row(1, -10, 1, 1, null, 1) ::
        Row(0, -1, 1, 1, 2, 0) ::
        Row(1, null, 1, 1, 2, 1) ::
        Row(1, 100, 1, 1, null, 1) ::
        Row(1, null, 2, 2, 3, 1) ::
        Row(0, null, 1, 1, null, 0) :: Nil)
  }
  //test最后基于AggregateExpression1实现
  test("test Last implemented based on AggregateExpression1") {
    // TODO: Remove this test once we remove AggregateExpression1.
    import org.apache.spark.sql.functions._
    val df = Seq((1, 1), (2, 2), (3, 3)).toDF("i", "j").repartition(1)
    withSQLConf(
      SQLConf.SHUFFLE_PARTITIONS.key -> "1",
      SQLConf.USE_SQL_AGGREGATE2.key -> "false") {

      checkAnswer(
        df.groupBy("i").agg(last("j")),
        df
      )
    }
  }
  //错误处理
  test("error handling") {
    withSQLConf("spark.sql.useAggregate2" -> "false") {
      val errorMessage = intercept[AnalysisException] {
        sqlContext.sql(
          """
            |SELECT
            |  key,
            |  sum(value + 1.5 * key),
            |  mydoublesum(value),
            |  mydoubleavg(value)
            |FROM agg1
            |GROUP BY key
          """.stripMargin).collect()
        //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      }.getMessage
      assert(errorMessage.contains("implemented based on the new Aggregate Function interface"))
    }

    // TODO: once we support Hive UDAF in the new interface,
    // we can remove the following two tests.
    //我们可以删除以下两个测试
    withSQLConf("spark.sql.useAggregate2" -> "true") {
      val errorMessage = intercept[AnalysisException] {
        sqlContext.sql(
          """
            |SELECT
            |  key,
            |  mydoublesum(value + 1.5 * key),
            |  stddev_samp(value)
            |FROM agg1
            |GROUP BY key
          """.stripMargin).collect()
        //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      }.getMessage
      assert(errorMessage.contains("implemented based on the new Aggregate Function interface"))

      // This will fall back to the old aggregate
      //这将回到旧的总计
      val newAggregateOperators = sqlContext.sql(
        """
          |SELECT
          |  key,
          |  sum(value + 1.5 * key),
          |  stddev_samp(value)
          |FROM agg1
          |GROUP BY key
        """.stripMargin).queryExecution.executedPlan.collect {
        case agg: aggregate.SortBasedAggregate => agg
        case agg: aggregate.TungstenAggregate => agg
      }
      val message =
        "We should fallback to the old aggregation code path if " +
          "there is any aggregate function that cannot be converted to the new interface."
      assert(newAggregateOperators.isEmpty, message)
    }
  }
  //自定义函数所有数据类型
  test("udaf with all data types") {
    val struct =
      StructType(
        StructField("f1", FloatType, true) ::
          StructField("f2", ArrayType(BooleanType), true) :: Nil)
    val dataTypes = Seq(StringType, BinaryType, NullType, BooleanType,
      ByteType, ShortType, IntegerType, LongType,
      FloatType, DoubleType, DecimalType(25, 5), DecimalType(6, 5),
      DateType, TimestampType,
      ArrayType(IntegerType), MapType(StringType, LongType), struct,
      new MyDenseVectorUDT())
    //现在我们将使用SortBasedAggregate来处理UDAF
    // Right now, we will use SortBasedAggregate to handle UDAFs.
    // UnsafeRow.mutableFieldTypes.asScala.toSeq will trigger SortBasedAggregate to use
    // UnsafeRow as the aggregation buffer. While, dataTypes will trigger
    // SortBasedAggregate to use a safe row as the aggregation buffer.
    //UnsafeRow.mutableFieldTypes.asScala.toSeq将触发SortBasedAggregate以使用UnsafeRow作为聚合缓冲区。
    //而dataTypes将触发SortBasedAggregate使用安全行作为聚合缓冲区。
    Seq(dataTypes, UnsafeRow.mutableFieldTypes.asScala.toSeq).foreach { dataTypes =>
      val fields = dataTypes.zipWithIndex.map { case (dataType, index) =>
        StructField(s"col$index", dataType, nullable = true)
      }
      // The schema used for data generator.
      //用于数据生成器的模式
      val schemaForGenerator = StructType(fields)
      // The schema used for the DataFrame df.
      //用于DataFrame df的模式
      val schema = StructType(StructField("id", IntegerType) +: fields)

      logInfo(s"Testing schema: ${schema.treeString}")

      val udaf = new ScalaAggregateFunction(schema)
      // Generate data at the driver side. We need to materialize the data first and then
      // create RDD.
      //在驱动程序端生成数据。 我们首先需要实现数据创建RDD
      val maybeDataGenerator =
        RandomDataGenerator.forType(
          dataType = schemaForGenerator,
          nullable = true,
          seed = Some(System.nanoTime()))
      val dataGenerator =
        maybeDataGenerator
          .getOrElse(fail(s"Failed to create data generator for schema $schemaForGenerator"))
      val data = (1 to 50).map { i =>
        dataGenerator.apply() match {
          case row: Row => Row.fromSeq(i +: row.toSeq)
          case null => Row.fromSeq(i +: Seq.fill(schemaForGenerator.length)(null))
          case other =>
            fail(s"Row or null is expected to be generated, " +
              s"but a ${other.getClass.getCanonicalName} is generated.")
        }
      }

      // Create a DF for the schema with random data.
      //使用随机数据为模式创建一个DF
      val rdd = sqlContext.sparkContext.parallelize(data, 1)
      val df = sqlContext.createDataFrame(rdd, schema)

      val allColumns = df.schema.fields.map(f => col(f.name))
      val expectedAnaswer =
        data
          .find(r => r.getInt(0) == 50)
          .getOrElse(fail("A row with id 50 should be the expected answer."))
      checkAnswer(
        df.groupBy().agg(udaf(allColumns: _*)),
        // udaf returns a Row as the output value.
        //udaf返回一行作为输出值
        Row(expectedAnaswer)
      )
    }
  }
}

class SortBasedAggregationQuerySuite extends AggregationQuerySuite {

  var originalUnsafeEnabled: Boolean = _

  override def beforeAll(): Unit = {
    originalUnsafeEnabled = sqlContext.conf.unsafeEnabled
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, "false")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, originalUnsafeEnabled.toString)
  }
}

class TungstenAggregationQuerySuite extends AggregationQuerySuite {

  var originalUnsafeEnabled: Boolean = _

  override def beforeAll(): Unit = {
    originalUnsafeEnabled = sqlContext.conf.unsafeEnabled
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, "true")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, originalUnsafeEnabled.toString)
  }
}

class TungstenAggregationQueryWithControlledFallbackSuite extends AggregationQuerySuite {

  var originalUnsafeEnabled: Boolean = _

  override def beforeAll(): Unit = {
    originalUnsafeEnabled = sqlContext.conf.unsafeEnabled
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, "true")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    sqlContext.setConf(SQLConf.UNSAFE_ENABLED.key, originalUnsafeEnabled.toString)
    sqlContext.conf.unsetConf("spark.sql.TungstenAggregate.testFallbackStartsAt")
  }

  override protected def checkAnswer(actual: DataFrame, expectedAnswer: Seq[Row]): Unit = {
    (0 to 2).foreach { fallbackStartsAt =>
      sqlContext.setConf(
        "spark.sql.TungstenAggregate.testFallbackStartsAt",
        fallbackStartsAt.toString)

      // Create a new df to make sure its physical operator picks up
      //创建一个新的df以确保其物理操作符拾取
      // spark.sql.TungstenAggregate.testFallbackStartsAt.

      val newActual = DataFrame(sqlContext, actual.logicalPlan)

      QueryTest.checkAnswer(newActual, expectedAnswer) match {
        case Some(errorMessage) =>
          val newErrorMessage =
            s"""
              |The following aggregation query failed when using TungstenAggregate with
              |controlled fallback (it falls back to sort-based aggregation once it has processed
              |$fallbackStartsAt input rows). The query is
              |${actual.queryExecution}
              |
              |$errorMessage
            """.stripMargin

          fail(newErrorMessage)
        case None =>
      }
    }
  }

  // Override it to make sure we call the actually overridden checkAnswer.
  //覆盖它，以确保我们调用实际覆盖的checkAnswer
  override protected def checkAnswer(df: DataFrame, expectedAnswer: Row): Unit = {
    checkAnswer(df, Seq(expectedAnswer))
  }

  // Override it to make sure we call the actually overridden checkAnswer.
  //覆盖它,以确保我们调用实际覆盖的checkAnswer
  override protected def checkAnswer(df: DataFrame, expectedAnswer: DataFrame): Unit = {
    checkAnswer(df, expectedAnswer.collect())
  }
}
