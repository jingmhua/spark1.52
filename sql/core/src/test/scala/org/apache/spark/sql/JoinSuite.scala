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

import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.execution.joins._
import org.apache.spark.sql.test.SharedSQLContext

//连接测试套件
class JoinSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  setupTestData()

  test("equi-join is hash-join") {//等值连接是哈希连接
    val x = testData2.as("x")
    val y = testData2.as("y")
    val join = x.join(y, $"x.a" === $"y.a", "inner").queryExecution.optimizedPlan
    val planned = ctx.planner.EquiJoinSelection(join)
    assert(planned.size === 1)
  }

  def assertJoin(sqlString: String, c: Class[_]): Any = {
    val df = sql(sqlString)
    val physical = df.queryExecution.sparkPlan
    val operators = physical.collect {
      case j: ShuffledHashJoin => j
      case j: ShuffledHashOuterJoin => j
      case j: LeftSemiJoinHash => j
      case j: BroadcastHashJoin => j
      case j: BroadcastHashOuterJoin => j
      case j: LeftSemiJoinBNL => j
      case j: CartesianProduct => j
      case j: BroadcastNestedLoopJoin => j
      case j: BroadcastLeftSemiJoinHash => j
      case j: SortMergeJoin => j
      case j: SortMergeOuterJoin => j
    }

    assert(operators.size === 1)
    if (operators(0).getClass() != c) {
      fail(s"$sqlString expected operator: $c, but got ${operators(0)}\n physical: \n$physical")
    }
  }

  test("join operator selection") {//连接操作选择
    ctx.cacheManager.clearCache()

    Seq(
      ("SELECT * FROM testData LEFT SEMI JOIN testData2 ON key = a", classOf[LeftSemiJoinHash]),
      ("SELECT * FROM testData LEFT SEMI JOIN testData2", classOf[LeftSemiJoinBNL]),
      ("SELECT * FROM testData JOIN testData2", classOf[CartesianProduct]),
      ("SELECT * FROM testData JOIN testData2 WHERE key = 2", classOf[CartesianProduct]),
      ("SELECT * FROM testData LEFT JOIN testData2", classOf[CartesianProduct]),
      ("SELECT * FROM testData RIGHT JOIN testData2", classOf[CartesianProduct]),
      ("SELECT * FROM testData FULL OUTER JOIN testData2", classOf[CartesianProduct]),
      ("SELECT * FROM testData LEFT JOIN testData2 WHERE key = 2", classOf[CartesianProduct]),
      ("SELECT * FROM testData RIGHT JOIN testData2 WHERE key = 2", classOf[CartesianProduct]),
      ("SELECT * FROM testData FULL OUTER JOIN testData2 WHERE key = 2", classOf[CartesianProduct]),
      ("SELECT * FROM testData JOIN testData2 WHERE key > a", classOf[CartesianProduct]),
      ("SELECT * FROM testData FULL OUTER JOIN testData2 WHERE key > a", classOf[CartesianProduct]),
      ("SELECT * FROM testData JOIN testData2 ON key = a", classOf[SortMergeJoin]),
      ("SELECT * FROM testData JOIN testData2 ON key = a and key = 2", classOf[SortMergeJoin]),
      ("SELECT * FROM testData JOIN testData2 ON key = a where key = 2", classOf[SortMergeJoin]),
      ("SELECT * FROM testData LEFT JOIN testData2 ON key = a", classOf[SortMergeOuterJoin]),
      ("SELECT * FROM testData RIGHT JOIN testData2 ON key = a where key = 2",
        classOf[SortMergeOuterJoin]),
      ("SELECT * FROM testData right join testData2 ON key = a and key = 2",
        classOf[SortMergeOuterJoin]),
      ("SELECT * FROM testData full outer join testData2 ON key = a",
        classOf[ShuffledHashOuterJoin]),
      ("SELECT * FROM testData left JOIN testData2 ON (key * a != key + a)",
        classOf[BroadcastNestedLoopJoin]),
      ("SELECT * FROM testData right JOIN testData2 ON (key * a != key + a)",
        classOf[BroadcastNestedLoopJoin]),
      ("SELECT * FROM testData full JOIN testData2 ON (key * a != key + a)",
        classOf[BroadcastNestedLoopJoin])
    ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      Seq(
        ("SELECT * FROM testData JOIN testData2 ON key = a", classOf[ShuffledHashJoin]),
        ("SELECT * FROM testData JOIN testData2 ON key = a and key = 2",
          classOf[ShuffledHashJoin]),
        ("SELECT * FROM testData JOIN testData2 ON key = a where key = 2",
          classOf[ShuffledHashJoin]),
        ("SELECT * FROM testData LEFT JOIN testData2 ON key = a", classOf[ShuffledHashOuterJoin]),
        ("SELECT * FROM testData RIGHT JOIN testData2 ON key = a where key = 2",
          classOf[ShuffledHashOuterJoin]),
        ("SELECT * FROM testData right join testData2 ON key = a and key = 2",
          classOf[ShuffledHashOuterJoin]),
        ("SELECT * FROM testData full outer join testData2 ON key = a",
          classOf[ShuffledHashOuterJoin])
      ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
    }
  }
//不应该使用不可排序的列
  test("SortMergeJoin shouldn't work on unsortable columns") {
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true") {
      Seq(
        ("SELECT * FROM arrayData JOIN complexData ON data = a", classOf[ShuffledHashJoin])
      ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
    }
  }
  //广播的哈希连接操作的选择
  test("broadcasted hash join operator selection") {
    ctx.cacheManager.clearCache()
    sql("CACHE TABLE testData")
    for (sortMergeJoinEnabled <- Seq(true, false)) {
      withClue(s"sortMergeJoinEnabled=$sortMergeJoinEnabled") {
        withSQLConf(SQLConf.SORTMERGE_JOIN.key -> s"$sortMergeJoinEnabled") {
          Seq(
            ("SELECT * FROM testData join testData2 ON key = a",
              classOf[BroadcastHashJoin]),
            ("SELECT * FROM testData join testData2 ON key = a and key = 2",
              classOf[BroadcastHashJoin]),
            ("SELECT * FROM testData join testData2 ON key = a where key = 2",
              classOf[BroadcastHashJoin])
          ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
        }
      }
    }
    sql("UNCACHE TABLE testData")
  }

  test("broadcasted hash outer join operator selection") {//广播的哈希外连接操作的选择
    ctx.cacheManager.clearCache()
    sql("CACHE TABLE testData")
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true") {
      Seq(
        ("SELECT * FROM testData LEFT JOIN testData2 ON key = a",
          classOf[SortMergeOuterJoin]),
        ("SELECT * FROM testData RIGHT JOIN testData2 ON key = a where key = 2",
          classOf[BroadcastHashOuterJoin]),
        ("SELECT * FROM testData right join testData2 ON key = a and key = 2",
          classOf[BroadcastHashOuterJoin])
      ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
    }
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      Seq(
        ("SELECT * FROM testData LEFT JOIN testData2 ON key = a",
          classOf[ShuffledHashOuterJoin]),
        ("SELECT * FROM testData RIGHT JOIN testData2 ON key = a where key = 2",
          classOf[BroadcastHashOuterJoin]),
        ("SELECT * FROM testData right join testData2 ON key = a and key = 2",
          classOf[BroadcastHashOuterJoin])
      ).foreach { case (query, joinClass) => assertJoin(query, joinClass) }
    }
    sql("UNCACHE TABLE testData")
  }

  test("multiple-key equi-join is hash-join") {
    val x = testData2.as("x")
    val y = testData2.as("y")
    val join = x.join(y, ($"x.a" === $"y.a") && ($"x.b" === $"y.b")).queryExecution.optimizedPlan
    val planned = ctx.planner.EquiJoinSelection(join)
    assert(planned.size === 1)
  }

  test("inner join where, one match per row") {//内部连接,每行一个匹配
    checkAnswer(
      upperCaseData.join(lowerCaseData).where('n === 'N),
      Seq(
        Row(1, "A", 1, "a"),
        Row(2, "B", 2, "b"),
        Row(3, "C", 3, "c"),
        Row(4, "D", 4, "d")
      ))
  }

  test("inner join ON, one match per row") {//内部连接,每行一个匹配
    checkAnswer(
      upperCaseData.join(lowerCaseData, $"n" === $"N"),
      Seq(
        Row(1, "A", 1, "a"),
        Row(2, "B", 2, "b"),
        Row(3, "C", 3, "c"),
        Row(4, "D", 4, "d")
      ))
  }

  test("inner join, where, multiple matches") {//内部连接,where,多个匹配
    val x = testData2.where($"a" === 1).as("x")
    val y = testData2.where($"a" === 1).as("y")
    checkAnswer(
      x.join(y).where($"x.a" === $"y.a"),
      Row(1, 1, 1, 1) ::
      Row(1, 1, 1, 2) ::
      Row(1, 2, 1, 1) ::
      Row(1, 2, 1, 2) :: Nil
    )
  }

  test("inner join, no matches") {//内部连接,没有匹配
    val x = testData2.where($"a" === 1).as("x")
    val y = testData2.where($"a" === 2).as("y")
    checkAnswer(
      x.join(y).where($"x.a" === $"y.a"),
      Nil)
  }

  test("big inner join, 4 matches per row") {//大的内部连接,每行4次
    val bigData = testData.unionAll(testData).unionAll(testData).unionAll(testData)
    val bigDataX = bigData.as("x")
    val bigDataY = bigData.as("y")

    checkAnswer(
      bigDataX.join(bigDataY).where($"x.key" === $"y.key"),
      testData.rdd.flatMap(row => Seq.fill(16)(Row.merge(row, row))).collect().toSeq)
  }

  test("cartisian product join") {//直角产品加入
    checkAnswer(
      testData3.join(testData3),
      Row(1, null, 1, null) ::
        Row(1, null, 2, 2) ::
        Row(2, 2, 1, null) ::
        Row(2, 2, 2, 2) :: Nil)
  }

  test("left outer join") {//左外连接
    checkAnswer(
      upperCaseData.join(lowerCaseData, $"n" === $"N", "left"),
      Row(1, "A", 1, "a") ::
        Row(2, "B", 2, "b") ::
        Row(3, "C", 3, "c") ::
        Row(4, "D", 4, "d") ::
        Row(5, "E", null, null) ::
        Row(6, "F", null, null) :: Nil)

    checkAnswer(
      upperCaseData.join(lowerCaseData, $"n" === $"N" && $"n" > 1, "left"),
      Row(1, "A", null, null) ::
        Row(2, "B", 2, "b") ::
        Row(3, "C", 3, "c") ::
        Row(4, "D", 4, "d") ::
        Row(5, "E", null, null) ::
        Row(6, "F", null, null) :: Nil)

    checkAnswer(
      upperCaseData.join(lowerCaseData, $"n" === $"N" && $"N" > 1, "left"),
      Row(1, "A", null, null) ::
        Row(2, "B", 2, "b") ::
        Row(3, "C", 3, "c") ::
        Row(4, "D", 4, "d") ::
        Row(5, "E", null, null) ::
        Row(6, "F", null, null) :: Nil)

    checkAnswer(
      upperCaseData.join(lowerCaseData, $"n" === $"N" && $"l" > $"L", "left"),
      Row(1, "A", 1, "a") ::
        Row(2, "B", 2, "b") ::
        Row(3, "C", 3, "c") ::
        Row(4, "D", 4, "d") ::
        Row(5, "E", null, null) ::
        Row(6, "F", null, null) :: Nil)

    // Make sure we are choosing left.outputPartitioning as the
    // outputPartitioning for the outer join operator.
    checkAnswer(
      sql(
        """
          |SELECT l.N, count(*)
          |FROM upperCaseData l LEFT OUTER JOIN allNulls r ON (l.N = r.a)
          |GROUP BY l.N
        """.stripMargin),
      Row(1, 1) ::
        Row(2, 1) ::
        Row(3, 1) ::
        Row(4, 1) ::
        Row(5, 1) ::
        Row(6, 1) :: Nil)

    checkAnswer(
      sql(
        """
          |SELECT r.a, count(*)
          |FROM upperCaseData l LEFT OUTER JOIN allNulls r ON (l.N = r.a)
          |GROUP BY r.a
        """.stripMargin),
      Row(null, 6) :: Nil)
  }

  test("right outer join") {//右外部联接
    checkAnswer(
      lowerCaseData.join(upperCaseData, $"n" === $"N", "right"),
      Row(1, "a", 1, "A") ::
        Row(2, "b", 2, "B") ::
        Row(3, "c", 3, "C") ::
        Row(4, "d", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)
    checkAnswer(
      lowerCaseData.join(upperCaseData, $"n" === $"N" && $"n" > 1, "right"),
      Row(null, null, 1, "A") ::
        Row(2, "b", 2, "B") ::
        Row(3, "c", 3, "C") ::
        Row(4, "d", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)
    checkAnswer(
      lowerCaseData.join(upperCaseData, $"n" === $"N" && $"N" > 1, "right"),
      Row(null, null, 1, "A") ::
        Row(2, "b", 2, "B") ::
        Row(3, "c", 3, "C") ::
        Row(4, "d", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)
    checkAnswer(
      lowerCaseData.join(upperCaseData, $"n" === $"N" && $"l" > $"L", "right"),
      Row(1, "a", 1, "A") ::
        Row(2, "b", 2, "B") ::
        Row(3, "c", 3, "C") ::
        Row(4, "d", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)

    // Make sure we are choosing right.outputPartitioning as the
    // outputPartitioning for the outer join operator.
    checkAnswer(
      sql(
        """
          |SELECT l.a, count(*)
          |FROM allNulls l RIGHT OUTER JOIN upperCaseData r ON (l.a = r.N)
          |GROUP BY l.a
        """.stripMargin),
      Row(null, 6))

    checkAnswer(
      sql(
        """
          |SELECT r.N, count(*)
          |FROM allNulls l RIGHT OUTER JOIN upperCaseData r ON (l.a = r.N)
          |GROUP BY r.N
        """.stripMargin),
      Row(1, 1) ::
        Row(2, 1) ::
        Row(3, 1) ::
        Row(4, 1) ::
        Row(5, 1) ::
        Row(6, 1) :: Nil)
  }

  test("full outer join") {//全外连接
    upperCaseData.where('N <= 4).registerTempTable("left")
    upperCaseData.where('N >= 3).registerTempTable("right")

    val left = UnresolvedRelation(Seq("left"), None)
    val right = UnresolvedRelation(Seq("right"), None)

    checkAnswer(
      left.join(right, $"left.N" === $"right.N", "full"),
      Row(1, "A", null, null) ::
        Row(2, "B", null, null) ::
        Row(3, "C", 3, "C") ::
        Row(4, "D", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)

    checkAnswer(
      left.join(right, ($"left.N" === $"right.N") && ($"left.N" !== 3), "full"),
      Row(1, "A", null, null) ::
        Row(2, "B", null, null) ::
        Row(3, "C", null, null) ::
        Row(null, null, 3, "C") ::
        Row(4, "D", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)

    checkAnswer(
      left.join(right, ($"left.N" === $"right.N") && ($"right.N" !== 3), "full"),
      Row(1, "A", null, null) ::
        Row(2, "B", null, null) ::
        Row(3, "C", null, null) ::
        Row(null, null, 3, "C") ::
        Row(4, "D", 4, "D") ::
        Row(null, null, 5, "E") ::
        Row(null, null, 6, "F") :: Nil)

    // Make sure we are UnknownPartitioning as the outputPartitioning for the outer join operator.
    //确保我们是未知的分区作为外连接运算符的输出分区
    checkAnswer(
      sql(
        """
          |SELECT l.a, count(*)
          |FROM allNulls l FULL OUTER JOIN upperCaseData r ON (l.a = r.N)
          |GROUP BY l.a
        """.stripMargin),
      Row(null, 10))

    checkAnswer(
      sql(
        """
          |SELECT r.N, count(*)
          |FROM allNulls l FULL OUTER JOIN upperCaseData r ON (l.a = r.N)
          |GROUP BY r.N
        """.stripMargin),
      Row(1, 1) ::
        Row(2, 1) ::
        Row(3, 1) ::
        Row(4, 1) ::
        Row(5, 1) ::
        Row(6, 1) ::
        Row(null, 4) :: Nil)

    checkAnswer(
      sql(
        """
          |SELECT l.N, count(*)
          |FROM upperCaseData l FULL OUTER JOIN allNulls r ON (l.N = r.a)
          |GROUP BY l.N
        """.stripMargin),
      Row(1, 1) ::
        Row(2, 1) ::
        Row(3, 1) ::
        Row(4, 1) ::
        Row(5, 1) ::
        Row(6, 1) ::
        Row(null, 4) :: Nil)

    checkAnswer(
      sql(
        """
          |SELECT r.a, count(*)
          |FROM upperCaseData l FULL OUTER JOIN allNulls r ON (l.N = r.a)
          |GROUP BY r.a
        """.stripMargin),
      Row(null, 10))
  }
  //广播左半连接操作的选择
  test("broadcasted left semi join operator selection") {
    ctx.cacheManager.clearCache()
    sql("CACHE TABLE testData")

    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "1000000000") {
      Seq(
        ("SELECT * FROM testData LEFT SEMI JOIN testData2 ON key = a",
          classOf[BroadcastLeftSemiJoinHash])
      ).foreach {
        case (query, joinClass) => assertJoin(query, joinClass)
      }
    }

    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      Seq(
        ("SELECT * FROM testData LEFT SEMI JOIN testData2 ON key = a", classOf[LeftSemiJoinHash])
      ).foreach {
        case (query, joinClass) => assertJoin(query, joinClass)
      }
    }

    sql("UNCACHE TABLE testData")
  }

  test("left semi join") {//左半连接
    val df = sql("SELECT * FROM testData2 LEFT SEMI JOIN testData ON key = a")
    checkAnswer(df,
      Row(1, 1) ::
        Row(1, 2) ::
        Row(2, 1) ::
        Row(2, 2) ::
        Row(3, 1) ::
        Row(3, 2) :: Nil)
  }
}
