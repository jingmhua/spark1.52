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

package org.apache.spark.sql.sources

import java.nio.charset.StandardCharsets
import java.sql.{Date, Timestamp}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._

class DefaultSource extends SimpleScanSource

class SimpleScanSource extends RelationProvider {
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String]): BaseRelation = {
    SimpleScan(parameters("from").toInt, parameters("TO").toInt)(sqlContext)
  }
}
//全表扫描 TableScan,@transient说明一个属性是临时的,不会被序列化
case class SimpleScan(from: Int, to: Int)(@transient val sqlContext: SQLContext)
  extends BaseRelation with TableScan {
  //StructType代表一张表,StructField代表一个字段
  override def schema: StructType =
    StructType(StructField("i", IntegerType, nullable = false) :: Nil)

  override def buildScan(): RDD[Row] = sqlContext.sparkContext.parallelize(from to to).map(Row(_))
}

class AllDataTypesScanSource extends SchemaRelationProvider {
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      schema: StructType): BaseRelation = {
    // Check that weird parameters are passed correctly.
    //检查奇怪的参数是否正确传递
    parameters("option_with_underscores")
    parameters("option.with.dots")

    AllDataTypesScan(parameters("from").toInt, parameters("TO").toInt, schema)(sqlContext)
  }
}

case class AllDataTypesScan(
    from: Int,
    to: Int,
    userSpecifiedSchema: StructType)(@transient val sqlContext: SQLContext)
  extends BaseRelation
  with TableScan {

  override def schema: StructType = userSpecifiedSchema

  override def needConversion: Boolean = true

  override def buildScan(): RDD[Row] = {
    sqlContext.sparkContext.parallelize(from to to).map { i =>
      Row(
        s"str_$i",
        s"str_$i".getBytes(StandardCharsets.UTF_8),
        i % 2 == 0,
        i.toByte,
        i.toShort,
        i,
        i.toLong,
        i.toFloat,
        i.toDouble,
        new java.math.BigDecimal(i),
        new java.math.BigDecimal(i),
        Date.valueOf("1970-01-01"),
        new Timestamp(20000 + i),
        s"varchar_$i",
        Seq(i, i + 1),
        Seq(Map(s"str_$i" -> Row(i.toLong))),
        Map(i -> i.toString),
        Map(Map(s"str_$i" -> i.toFloat) -> Row(i.toLong)),
        Row(i, i.toString),
          Row(Seq(s"str_$i", s"str_${i + 1}"),
            Row(Seq(Date.valueOf(s"1970-01-${i + 1}")))))
    }
  }
}
//表扫描测试套件
class TableScanSuite extends DataSourceTest with SharedSQLContext {
  protected override lazy val sql = caseInsensitiveContext.sql _

  private lazy val tableWithSchemaExpected = (1 to 10).map { i =>
    Row(
      s"str_$i",
      s"str_$i",
      i % 2 == 0,
      i.toByte,
      i.toShort,
      i,
      i.toLong,
      i.toFloat,
      i.toDouble,
      new java.math.BigDecimal(i),
      new java.math.BigDecimal(i),
      Date.valueOf("1970-01-01"),
      new Timestamp(20000 + i),
      s"varchar_$i",
      Seq(i, i + 1),
      Seq(Map(s"str_$i" -> Row(i.toLong))),
      Map(i -> i.toString),
      Map(Map(s"str_$i" -> i.toFloat) -> Row(i.toLong)),
      Row(i, i.toString),
      Row(Seq(s"str_$i", s"str_${i + 1}"), Row(Seq(Date.valueOf(s"1970-01-${i + 1}")))))
  }.toSeq

  override def beforeAll(): Unit = {
    super.beforeAll()
    sql(
      """
        |CREATE TEMPORARY TABLE oneToTen
        |USING org.apache.spark.sql.sources.SimpleScanSource
        |OPTIONS (
        |  From '1',
        |  To '10',
        |  option_with_underscores 'someval',
        |  option.with.dots 'someval'
        |)
      """.stripMargin)

    sql(
      """
        |CREATE TEMPORARY TABLE tableWithSchema (
        |`string$%Field` stRIng,
        |binaryField binary,
        |`booleanField` boolean,
        |ByteField tinyint,
        |shortField smaLlint,
        |int_Field iNt,
        |`longField_:,<>=+/~^` Bigint,
        |floatField flOat,
        |doubleField doubLE,
        |decimalField1 decimal,
        |decimalField2 decimal(9,2),
        |dateField dAte,
        |timestampField tiMestamp,
        |varcharField varchaR(12),
        |arrayFieldSimple Array<inT>,
        |arrayFieldComplex Array<Map<String, Struct<key:bigInt>>>,
        |mapFieldSimple MAP<iNt, StRing>,
        |mapFieldComplex Map<Map<stRING, fLOAT>, Struct<key:bigInt>>,
        |structFieldSimple StRuct<key:INt, Value:STrINg>,
        |structFieldComplex StRuct<key:Array<String>, Value:struct<`value_(2)`:Array<date>>>
        |)
        |USING org.apache.spark.sql.sources.AllDataTypesScanSource
        |OPTIONS (
        |  From '1',
        |  To '10',
        |  option_with_underscores 'someval',
        |  option.with.dots 'someval'
        |)
      """.stripMargin)
  }

  sqlTest(
    "SELECT * FROM oneToTen",
    (1 to 10).map(Row(_)).toSeq)

  sqlTest(
    "SELECT i FROM oneToTen",
    (1 to 10).map(Row(_)).toSeq)

  sqlTest(
    "SELECT i FROM oneToTen WHERE i < 5",
    (1 to 4).map(Row(_)).toSeq)

  sqlTest(
    "SELECT i * 2 FROM oneToTen",
    (1 to 10).map(i => Row(i * 2)).toSeq)

  sqlTest(
    "SELECT a.i, b.i FROM oneToTen a JOIN oneToTen b ON a.i = b.i + 1",
    (2 to 10).map(i => Row(i, i - 1)).toSeq)

  test("Schema and all fields") {//模式所有字段
    val expectedSchema = StructType(
      StructField("string$%Field", StringType, true) ::
      StructField("binaryField", BinaryType, true) ::
      StructField("booleanField", BooleanType, true) ::
      StructField("ByteField", ByteType, true) ::
      StructField("shortField", ShortType, true) ::
      StructField("int_Field", IntegerType, true) ::
      StructField("longField_:,<>=+/~^", LongType, true) ::
      StructField("floatField", FloatType, true) ::
      StructField("doubleField", DoubleType, true) ::
      StructField("decimalField1", DecimalType.USER_DEFAULT, true) ::
      StructField("decimalField2", DecimalType(9, 2), true) ::
      StructField("dateField", DateType, true) ::
      StructField("timestampField", TimestampType, true) ::
      StructField("varcharField", StringType, true) ::
      StructField("arrayFieldSimple", ArrayType(IntegerType), true) ::
      StructField("arrayFieldComplex",
        ArrayType(//StructType代表一张表,StructField代表一个字段
          MapType(StringType, StructType(StructField("key", LongType, true) :: Nil))), true) ::
      StructField("mapFieldSimple", MapType(IntegerType, StringType), true) ::
      StructField("mapFieldComplex",
        MapType(
          MapType(StringType, FloatType),
          StructType(StructField("key", LongType, true) :: Nil)), true) ::
      StructField("structFieldSimple",
        StructType(
          StructField("key", IntegerType, true) ::
          StructField("Value", StringType, true) ::  Nil), true) ::
      StructField("structFieldComplex",
        StructType(
          StructField("key", ArrayType(StringType), true) ::
          StructField("Value",
            StructType(
              StructField("value_(2)", ArrayType(DateType), true) :: Nil), true) ::  Nil), true) ::
      Nil
    )

    assert(expectedSchema == caseInsensitiveContext.table("tableWithSchema").schema)

    checkAnswer(
      sql(
        """SELECT
          | `string$%Field`,
          | cast(binaryField as string),
          | booleanField,
          | byteField,
          | shortField,
          | int_Field,
          | `longField_:,<>=+/~^`,
          | floatField,
          | doubleField,
          | decimalField1,
          | decimalField2,
          | dateField,
          | timestampField,
          | varcharField,
          | arrayFieldSimple,
          | arrayFieldComplex,
          | mapFieldSimple,
          | mapFieldComplex,
          | structFieldSimple,
          | structFieldComplex FROM tableWithSchema""".stripMargin),
      tableWithSchemaExpected
    )
  }

  sqlTest(
    "SELECT count(*) FROM tableWithSchema",
    Seq(Row(10)))

  sqlTest(
    "SELECT `string$%Field` FROM tableWithSchema",
    (1 to 10).map(i => Row(s"str_$i")).toSeq)

  sqlTest(
    "SELECT int_Field FROM tableWithSchema WHERE int_Field < 5",
    (1 to 4).map(Row(_)).toSeq)

  sqlTest(
    "SELECT `longField_:,<>=+/~^` * 2 FROM tableWithSchema",
    (1 to 10).map(i => Row(i * 2.toLong)).toSeq)

  sqlTest(
    "SELECT structFieldSimple.key, arrayFieldSimple[1] FROM tableWithSchema a where int_Field=1",
    Seq(Row(1, 2)))

  sqlTest(
    "SELECT structFieldComplex.Value.`value_(2)` FROM tableWithSchema",
    (1 to 10).map(i => Row(Seq(Date.valueOf(s"1970-01-${i + 1}")))).toSeq)

  test("Caching")  {
    // Cached Query Execution 执行缓存查询
    caseInsensitiveContext.cacheTable("oneToTen")
    assertCached(sql("SELECT * FROM oneToTen"))
    checkAnswer(
      sql("SELECT * FROM oneToTen"),
      (1 to 10).map(Row(_)).toSeq)

    assertCached(sql("SELECT i FROM oneToTen"))
    checkAnswer(
      sql("SELECT i FROM oneToTen"),
      (1 to 10).map(Row(_)).toSeq)

    assertCached(sql("SELECT i FROM oneToTen WHERE i < 5"))
    checkAnswer(
      sql("SELECT i FROM oneToTen WHERE i < 5"),
      (1 to 4).map(Row(_)).toSeq)

    assertCached(sql("SELECT i * 2 FROM oneToTen"))
    checkAnswer(
      sql("SELECT i * 2 FROM oneToTen"),
      (1 to 10).map(i => Row(i * 2)).toSeq)

    assertCached(sql(
      "SELECT a.i, b.i FROM oneToTen a JOIN oneToTen b ON a.i = b.i + 1"), 2)
    checkAnswer(sql(
      "SELECT a.i, b.i FROM oneToTen a JOIN oneToTen b ON a.i = b.i + 1"),
      (2 to 10).map(i => Row(i, i - 1)).toSeq)

    // Verify uncaching 验证未缓存
    caseInsensitiveContext.uncacheTable("oneToTen")
    assertCached(sql("SELECT * FROM oneToTen"), 0)
  }

  test("defaultSource") {//默认数据源
    sql(
      """
        |CREATE TEMPORARY TABLE oneToTenDef
        |USING org.apache.spark.sql.sources
        |OPTIONS (
        |  from '1',
        |  to '10'
        |)
      """.stripMargin)

    checkAnswer(
      sql("SELECT * FROM oneToTenDef"),
      (1 to 10).map(Row(_)).toSeq)
  }

  test("exceptions") {//异常
    // Make sure we do throw correct exception when users use a relation provider that
    // only implements the RelationProvier or the SchemaRelationProvider.
    val schemaNotAllowed = intercept[Exception] {
      sql(
        """
          |CREATE TEMPORARY TABLE relationProvierWithSchema (i int)
          |USING org.apache.spark.sql.sources.SimpleScanSource
          |OPTIONS (
          |  From '1',
          |  To '10'
          |)
        """.stripMargin)
    }
    assert(schemaNotAllowed.getMessage.contains("does not allow user-specified schemas"))

    val schemaNeeded = intercept[Exception] {
      sql(
        """
          |CREATE TEMPORARY TABLE schemaRelationProvierWithoutSchema
          |USING org.apache.spark.sql.sources.AllDataTypesScanSource
          |OPTIONS (
          |  From '1',
          |  To '10'
          |)
        """.stripMargin)
    }
    assert(schemaNeeded.getMessage.contains("A schema needs to be specified when using"))
  }

  test("SPARK-5196 schema field with comment") {//模式字段与注释
    sql(
      """
       |CREATE TEMPORARY TABLE student(name string comment "SN", age int comment "SA", grade int)
       |USING org.apache.spark.sql.sources.AllDataTypesScanSource
       |OPTIONS (
       |  from '1',
       |  to '10',
       |  option_with_underscores 'someval',
       |  option.with.dots 'someval'
       |)
       """.stripMargin)

       val planned = sql("SELECT * FROM student").queryExecution.executedPlan
       val comments = planned.schema.fields.map { field =>
         if (field.metadata.contains("comment")) field.metadata.getString("comment")
         else "NO_COMMENT"
       }.mkString(",")

    assert(comments === "SN,SA,NO_COMMENT")
  }
}
