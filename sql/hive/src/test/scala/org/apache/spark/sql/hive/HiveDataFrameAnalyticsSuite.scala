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

package org.apache.spark.sql.hive

import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.hive.test.TestHive.implicits._
import org.scalatest.BeforeAndAfterAll

// TODO ideally we should put the test suite into the package `sql`, as
//TODO理想情况下,我们应该将测试套件放入“sql”包中
// `hive` package is optional in compiling, however, `SQLContext.sql` doesn't
// support the `cube` or `rollup` yet. 不支持“cube”或“rollup”
//Hive DataFrame分析套件
class HiveDataFrameAnalyticsSuite extends QueryTest with BeforeAndAfterAll {
  private var testData: DataFrame = _

  override def beforeAll() {
    testData = Seq((1, 2), (2, 4)).toDF("a", "b")
    TestHive.registerDataFrameAsTable(testData, "mytable")
  }

  override def afterAll(): Unit = {
    TestHive.dropTempTable("mytable")
  }

  /**
    * group by 后带 rollup 子句的功能可以理解为：先按一定的规则产生多种分组，然后按各种分组统计数据
    * (至于统计出的数据是求和还是最大值还是平均值等这就取决于SELECT后的聚合函数)
    * group by 后带 rollup 子句所返回的结果集,可以理解为各个分组所产生的结果集的并集且没有去掉重复数据
    */
  test("rollup") {
    checkAnswer(
      //DataFrame.rollup
      testData.rollup($"a" + $"b", $"b").agg(sum($"a" - $"b")),
      sql("select a + b, b, sum(a - b) from mytable group by a + b, b with rollup").collect()
    )

    checkAnswer(
      testData.rollup("a", "b").agg(sum("b")),
      sql("select a, b, sum(b) from mytable group by a, b with rollup").collect()
    )
  }
  /**
    * CUBE 运算符在 SELECT 语句的 GROUP BY 子句中指定。该语句的选择列表包含维度列和聚合函数表达式。
    * GROUP BY 指定了维度列和关键字 WITH CUBE,结果集包含维度列中各值的所有可能组合,以及与这些维度值组合相匹配的基础行中的聚合值。
    */
  test("cube") {
    checkAnswer(
      testData.cube($"a" + $"b", $"b").agg(sum($"a" - $"b")),
      sql("select a + b, b, sum(a - b) from mytable group by a + b, b with cube").collect()
    )

    checkAnswer(
      testData.cube("a", "b").agg(sum("b")),
      sql("select a, b, sum(b) from mytable group by a, b with cube").collect()
    )
  }
}
