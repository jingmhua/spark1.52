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

package org.apache.spark.rdd

import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.mapred._
import org.apache.hadoop.util.Progressable

import scala.collection.mutable.{ ArrayBuffer, HashSet }
import scala.util.Random

import org.apache.hadoop.conf.{ Configurable, Configuration }
import org.apache.hadoop.mapreduce.{
  JobContext => NewJobContext,
  OutputCommitter => NewOutputCommitter,
  OutputFormat => NewOutputFormat,
  RecordWriter => NewRecordWriter,
  TaskAttemptContext => NewTaskAttempContext
}
import org.apache.spark.{ Partitioner, SharedSparkContext, SparkFunSuite }
import org.apache.spark.util.Utils
/**
 * PairRDDFunctions主要是Key/Value对操作
 */
class PairRDDFunctionsSuite extends SparkFunSuite with SharedSparkContext {

  test("aggregateByKey") { //根据Key聚合操作
    val pairs = sc.parallelize(Array((1, 1), (1, 1), (3, 2), (5, 1), (5, 3)), 2)

    val sets = pairs.aggregateByKey(new HashSet[Int]())(_ += _, _ ++= _).collect() //tuple元组
    // Array((1,Set(1)), (3,Set(2)), (5,Set(1, 3))),元组取值方式
    assert(sets.size === 3)
    val valuesFor1 = sets.find(_._1 == 1).get._2 //查找k==1,Set(1),取出map value值
    assert(valuesFor1.toList.sorted === List(1))
    val valuesFor3 = sets.find(_._1 == 3).get._2
    assert(valuesFor3.toList.sorted === List(2))
    val valuesFor5 = sets.find(_._1 == 5).get._2
    assert(valuesFor5.toList.sorted === List(1, 3))
  }

  test("groupByKey") { //RDD的各个元素根据这个key进行分组
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (2, 1)))
    val groups = pairs.groupByKey().collect() //RDD的各个元素根据这个key进行分组
    assert(groups.size === 2)
    //groups: Array[(Int, Iterable[Int])] = Array((1,CompactBuffer(1, 2, 3)), (2,CompactBuffer(1)))
    val valuesFor1 = groups.find(_._1 == 1).get._2 //查找k==1,Set(1),取出map value值
    assert(valuesFor1.toList.sorted === List(1, 2, 3))
    val valuesFor2 = groups.find(_._1 == 2).get._2
    assert(valuesFor2.toList.sorted === List(1))
  }

  test("groupByKey with duplicates") { //重复
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    val groups = pairs.groupByKey().collect()
    /**
     * 结果
     * Array(
     * (1,ArrayBuffer(1, 2, 3)),
     * (2,ArrayBuffer(1))
     * )*
     */
    assert(groups.size === 2)
    val valuesFor1 = groups.find(_._1 == 1).get._2
    assert(valuesFor1.toList.sorted === List(1, 1, 2, 3)) //包括重复数据
    val valuesFor2 = groups.find(_._1 == 2).get._2
    assert(valuesFor2.toList.sorted === List(1))
  }

  test("groupByKey with negative(负数) key hash codes") {//key值负数
    val pairs = sc.parallelize(Array((-1, 1), (-1, 2), (-1, 3), (2, 1)))
    val groups = pairs.groupByKey().collect()
    /* 
		结果 
    Array( 
    (-1,ArrayBuffer(1, 2, 3)), 
    (2,ArrayBuffer(1)) 
    ) 
		*/
    assert(groups.size === 2)
    val valuesForMinus1 = groups.find(_._1 == -1).get._2
    assert(valuesForMinus1.toList.sorted === List(1, 2, 3))
    val valuesFor2 = groups.find(_._1 == 2).get._2
    assert(valuesFor2.toList.sorted === List(1))
  }

  test("groupByKey with many output partitions") { //可能输出多个分区
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (2, 1)))
    val groups = pairs.groupByKey(10).collect()
    /* 
		结果 
    Array( 
    (1,ArrayBuffer(1, 2, 3)), 
    (2,ArrayBuffer(1)) 
    ) 
		*/
    assert(groups.size === 2)
    val valuesFor1 = groups.find(_._1 == 1).get._2
    assert(valuesFor1.toList.sorted === List(1, 2, 3))
    val valuesFor2 = groups.find(_._1 == 2).get._2
    assert(valuesFor2.toList.sorted === List(1))
  }

  test("sampleByKey") {

    val defaultSeed = 1L

    // vary RDD size RDD大小不同
    for (n <- List(100, 1000, 1000000)) {
      val data = sc.parallelize(1 to n, 2)
      val fractionPositive = 0.3
      val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))
      val samplingRate = 0.1
      StratifiedAuxiliary.testSample(stratifiedData, samplingRate, defaultSeed, n)
    }

    // vary fractionPositive 变化分数阳性
    for (fractionPositive <- List(0.1, 0.3, 0.5, 0.7, 0.9)) {
      val n = 100
      val data = sc.parallelize(1 to n, 2)
      val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))
      val samplingRate = 0.1
      StratifiedAuxiliary.testSample(stratifiedData, samplingRate, defaultSeed, n)
    }

    // Use the same data for the rest of the tests
    //使用相同的数据用于测试的其余部分
    val fractionPositive = 0.3
    val n = 100
    val data = sc.parallelize(1 to n, 2)
    val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))

    // vary seed 不同的种子
    for (seed <- defaultSeed to defaultSeed + 5L) {
      val samplingRate = 0.1
      StratifiedAuxiliary.testSample(stratifiedData, samplingRate, seed, n)
    }

    // vary sampling rate
    //不同的采样率
    for (samplingRate <- List(0.01, 0.05, 0.1, 0.5)) {
      StratifiedAuxiliary.testSample(stratifiedData, samplingRate, defaultSeed, n)
    }
  }

  test("sampleByKeyExact") {//按关键字提取样本
    val defaultSeed = 1L
    //spark对于分层抽样支持两个版本sampleByKey和sampleByKeyExact。
    //它是一个根据RDD的Key-Value来抽样的功能,可以为每个key设置其被选中的概率
    // vary RDD size
    for (n <- List(100, 1000, 1000000)) {
      val data = sc.parallelize(1 to n, 2)
      val fractionPositive = 0.3
      val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))
      val samplingRate = 0.1
      StratifiedAuxiliary.testSampleExact(stratifiedData, samplingRate, defaultSeed, n)
    }

    // vary fractionPositive
    for (fractionPositive <- List(0.1, 0.3, 0.5, 0.7, 0.9)) {
      val n = 100
      val data = sc.parallelize(1 to n, 2)
      val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))
      val samplingRate = 0.1
      StratifiedAuxiliary.testSampleExact(stratifiedData, samplingRate, defaultSeed, n)
    }

    // Use the same data for the rest of the tests
    //对于其余的测试使用相同的数据
    val fractionPositive = 0.3
    val n = 100
    val data = sc.parallelize(1 to n, 2)
    val stratifiedData = data.keyBy(StratifiedAuxiliary.stratifier(fractionPositive))

    // vary seed 种子变化
    for (seed <- defaultSeed to defaultSeed + 5L) {
      val samplingRate = 0.1
      StratifiedAuxiliary.testSampleExact(stratifiedData, samplingRate, seed, n)
    }

    // vary sampling rate 不同的采样率
    for (samplingRate <- List(0.01, 0.05, 0.1, 0.5)) {
      StratifiedAuxiliary.testSampleExact(stratifiedData, samplingRate, defaultSeed, n)
    }
  }

  test("reduceByKey") {
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    //数据集调用时,返回一个(K,V)对数据集,使用指定的reduce函数,将相同key的值聚合到一起
    val sums = pairs.reduceByKey(_ + _).collect()
    //返回(1, 7), (2, 1)
    assert(sums.toSet === Set((1, 7), (2, 1)))
  }

  test("reduceByKey with collectAsMap") {
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    val sums = pairs.reduceByKey(_ + _).collectAsMap() //返回Map类型,Map(2 -> 1, 1 -> 7)
    assert(sums.size === 2)
    assert(sums(1) === 7) //map取值
    assert(sums(2) === 1)
  }

  test("reduceByKey with many output partitons") { //输出多个分区
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    val sums = pairs.reduceByKey(_ + _, 10).collect()
    assert(sums.toSet === Set((1, 7), (2, 1)))
  }

  test("reduceByKey with partitioner") {//分区
    val p = new Partitioner() {
      def numPartitions = 2
      def getPartition(key: Any) = key.asInstanceOf[Int]
    }
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 1), (0, 1))).partitionBy(p)
    val sums = pairs.reduceByKey(_ + _)
    assert(sums.collect().toSet === Set((1, 4), (0, 1)))
    assert(sums.partitioner === Some(p))
    // count the dependencies to make sure there is only 1 ShuffledRDD
    //统计依赖RDD,确保只有1 shuffledrdd
    val deps = new HashSet[RDD[_]]()
    def visit(r: RDD[_]) { //
      for (dep <- r.dependencies) {
        deps += dep.rdd
        visit(dep.rdd) //递归
      }
    }
    visit(sums)
    for (p <- deps) {//打印依赖RDD名称
      println(p)
    }
    assert(deps.size === 2) // ShuffledRDD, ParallelCollection.
  }

  test("countApproxDistinctByKey") {
    def error(est: Long, size: Long): Double = math.abs(est - size) / size.toDouble

    /* Since HyperLogLog unique counting is approximate, and the relative standard deviation is
     * only a statistical bound, the tests can fail for large values of relativeSD. We will be using
     * relatively tight error bounds to check correctness of functionality rather than checking
     * whether the approximation conforms with the requested bound.
     */
    val p = 20
    val sp = 0
    // When p = 20, the relative accuracy is about 0.001. So with high probability, the
    // relative error should be smaller than the threshold 0.01 we use here.
    //当p = 20时，相对精度约为0.001。 所以有很高的概率
    //相对误差应该小于我们在这里使用的阈值0.01。
    val relativeSD = 0.01

    // For each value i, there are i tuples with first element equal to i.
    // Therefore, the expected count for key i would be i.
    //对于每个值，我有第一个元素等于i的元组。
    //因此，关键我的预期计数将是i。
    val stacked = (1 to 100).flatMap(i => (1 to i).map(j => (i, j)))
    val rdd1 = sc.parallelize(stacked)
    //countApproxDistinctByKey作用是对RDD集合内容进行去重统计,统计是一个大约的统计
    val counted1 = rdd1.countApproxDistinctByKey(p, sp).collect()
    counted1.foreach { case (k, count) => assert(error(count, k) < relativeSD) }

    val rnd = new Random(42)

    // The expected count for key num would be num
    //键值num的预期计数将为num
    val randStacked = (1 to 100).flatMap { i =>
      val num = rnd.nextInt() % 500
      (1 to num).map(j => (num, j))
    }
    val rdd2 = sc.parallelize(randStacked)
    val counted2 = rdd2.countApproxDistinctByKey(relativeSD).collect()
    counted2.foreach {
      case (k, count) =>
        assert(error(count, k) < relativeSD, s"${error(count, k)} < $relativeSD")
    }
  }

  test("join") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val joined = rdd1.join(rdd2).collect() //对两个key-value的RDD执行内连接(Key相同的value值连接),去掉key值不相同的值
    assert(joined.size === 4)
    assert(joined.toSet === Set(
      (1, (1, 'x')),
      (1, (2, 'x')),
      (2, (1, 'y')),
      (2, (1, 'z'))))
  }

  test("join all-to-all") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (1, 3)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (1, 'y')))
    val joined = rdd1.join(rdd2).collect()
    assert(joined.size === 6)
    assert(joined.toSet === Set( //全连接
      (1, (1, 'x')),
      (1, (1, 'y')),
      (1, (2, 'x')),
      (1, (2, 'y')),
      (1, (3, 'x')),
      (1, (3, 'y'))))
  }

  test("leftOuterJoin") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val joined = rdd1.leftOuterJoin(rdd2).collect() //左连接
    assert(joined.size === 5)
    assert(joined.toSet === Set(
      (1, (1, Some('x'))),
      (1, (2, Some('x'))),
      (2, (1, Some('y'))),
      (2, (1, Some('z'))),
      (3, (1, None))))//没有对应的值为None
  }

  // See SPARK-9326
  test("cogroup with empty RDD") {//与空RDD共同组合
    import scala.reflect.classTag
    val intPairCT = classTag[(Int, Int)]
    //做集合性操作的基础api,包括各种join、求交等
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.emptyRDD[(Int, Int)](intPairCT)

    val joined = rdd1.cogroup(rdd2).collect()
    assert(joined.size > 0)
  }

  // See SPARK-9326
  test("cogroup with groupByed RDD having 0 partitions") {//与GroupByed RDD共享具有0个分区
    import scala.reflect.classTag
    val intCT = classTag[Int]

    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.emptyRDD[Int](intCT).groupBy((x) => 5)
    val joined = rdd1.cogroup(rdd2).collect()
    assert(joined.size > 0)
  }

  test("rightOuterJoin") {//右外连接
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val joined = rdd1.rightOuterJoin(rdd2).collect()
    assert(joined.size === 5)
    assert(joined.toSet === Set( //右连接
      (1, (Some(1), 'x')),
      (1, (Some(2), 'x')),
      (2, (Some(1), 'y')),
      (2, (Some(1), 'z')),
      (4, (None, 'w'))))
  }

  test("fullOuterJoin") { //全连接
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val joined = rdd1.fullOuterJoin(rdd2).collect()
    assert(joined.size === 6)
    assert(joined.toSet === Set(
      (1, (Some(1), Some('x'))),
      (1, (Some(2), Some('x'))),
      (2, (Some(1), Some('y'))),
      (2, (Some(1), Some('z'))),
      (3, (Some(1), None)),
      (4, (None, Some('w')))))
  }

  test("join with no matches") { //连接没有匹配的Key
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((4, 'x'), (5, 'y'), (5, 'z'), (6, 'w')))
    val joined = rdd1.join(rdd2).collect()
    assert(joined.size === 0)
  }

  test("join with many output partitions") { //连接多个输出分区
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val joined = rdd1.join(rdd2, 10).collect()
    assert(joined.size === 4)
    assert(joined.toSet === Set(
      (1, (1, 'x')),
      (1, (2, 'x')),
      (2, (1, 'y')),
      (2, (1, 'z'))))
  }

  test("groupWith") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    /**
     * groupWith 将RDD rdd2与对象自身进行连接,并按key进行分组,返回一个数据集,组成元素为(K, Seq[V], Seq[W]) Tuples
     */
    val joined = rdd1.groupWith(rdd2).collect()
    assert(joined.size === 4)
    /**
     * x._2._1.toList 第一个rdd1 K值相同的,
     * x._2._2.toList 第二个rdd2 K值相同的
     */
    val joinedSet = joined.map(x => (x._1, (x._2._1.toList, x._2._2.toList))).toSet
    assert(joinedSet === Set(
      (1, (List(1, 2), List('x'))),
      (2, (List(1), List('y', 'z'))),
      (3, (List(1), List())),
      (4, (List(), List('w')))))
  }

  test("groupWith3") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val rdd3 = sc.parallelize(Array((1, 'a'), (3, 'b'), (4, 'c'), (4, 'd')))
    /**
     * groupWith 将RDD rdd2与对象自身进行连接,并按key进行分组,返回一个数据集,组成元素为(K, Seq[V], Seq[W]) Tuples
     */
    val joined = rdd1.groupWith(rdd2, rdd3).collect()
    assert(joined.size === 4)
    val joinedSet = joined.map(x => (x._1,
      (x._2._1.toList, x._2._2.toList, x._2._3.toList))).toSet
    assert(joinedSet === Set(
      (1, (List(1, 2), List('x'), List('a'))),
      (2, (List(1), List('y', 'z'), List())),
      (3, (List(1), List(), List('b'))),
      (4, (List(), List('w'), List('c', 'd')))))
  }

  test("groupWith4") {
    val rdd1 = sc.parallelize(Array((1, 1), (1, 2), (2, 1), (3, 1)))
    val rdd2 = sc.parallelize(Array((1, 'x'), (2, 'y'), (2, 'z'), (4, 'w')))
    val rdd3 = sc.parallelize(Array((1, 'a'), (3, 'b'), (4, 'c'), (4, 'd')))
    val rdd4 = sc.parallelize(Array((2, '@')))
    /**
     * groupWith 将RDD rdd2与对象自身进行连接,并按key进行分组,返回一个数据集,组成元素为(K, Seq[V], Seq[W]) Tuples
     */
    val joined = rdd1.groupWith(rdd2, rdd3, rdd4).collect()
    assert(joined.size === 4)
    val joinedSet = joined.map(x => (x._1,
      (x._2._1.toList, x._2._2.toList, x._2._3.toList, x._2._4.toList))).toSet
    assert(joinedSet === Set(
      (1, (List(1, 2), List('x'), List('a'), List())),
      (2, (List(1), List('y', 'z'), List(), List('@'))),
      (3, (List(1), List(), List('b'), List())),
      (4, (List(), List('w'), List('c', 'd'), List()))))
  }

  test("zero-partition RDD") {//RDD空的分区
    val emptyDir = Utils.createTempDir()
    try {
      val file = sc.textFile(emptyDir.getAbsolutePath)
      assert(file.partitions.isEmpty)
      assert(file.collect().toList === Nil)//列表结尾为Nil
      // Test that a shuffle on the file works, because this used to be a bug
      //在文件上进行Shuffle的测试,因为这曾经是一个错误
      assert(file.map(line => (line, 1)).reduceByKey(_ + _).collect().toList === Nil)
    } finally {
      Utils.deleteRecursively(emptyDir)
    }
  }

  test("keys and values") {//转换Keys和values值
    val rdd = sc.parallelize(Array((1, "a"), (2, "b")))
    //返回一个仅包含键值的RDD
    assert(rdd.keys.collect().toList === List(1, 2))
    //返回一个仅包含值的RDD
    assert(rdd.values.collect().toList === List("a", "b"))
  }
  //使用默认的分区的大小
  test("default partitioner uses partition size") {
    // specify 2000 partitions 指定2000个分区
    val a = sc.makeRDD(Array(1, 2, 3, 4), 2000)
    // do a map, which loses the partitioner
    //失去的分割
    val b = a.map(a => (a, (a * 2).toString))
    // then a group by, and see we didn't revert to 2 partitions
    //然后分组,看到我们没有恢复到2个分区
    val c = b.groupByKey()
    assert(c.partitions.size === 2000)//获得分区大小
  }
  //默认的分区,使用的最大分区
  test("default partitioner uses largest partitioner") {
    val a = sc.makeRDD(Array((1, "a"), (2, "b")), 2)
    val b = sc.makeRDD(Array((1, "a"), (2, "b")), 2000)

    val c = a.join(b)
    assert(c.partitions.size === 2000)//连接操作使用最大分区
  }

  test("subtract") { //返回在RDD中出现,并且不在otherRDD中出现的元素,不去重
    //去除两个RDD中相同的元素,不同的RDD将保留下来 
    val a = sc.parallelize(Array(1, 2, 3), 2)
    val b = sc.parallelize(Array(2, 3, 4), 4)
    //subtract 本RDD有、它RDD无的元素留下来
    val c = a.subtract(b)
    assert(c.collect().toSet === Set(1))//并集
    assert(c.partitions.size === a.partitions.size)//分区数相等
  }
  //去除两个RDD中相同的元素,不同的RDD将保留下来 窄依赖
  test("subtract with narrow dependency") {
    // use a deterministic partitioner
    //使用一个确定性的分割
    val p = new Partitioner() {
      def numPartitions = 5
      def getPartition(key: Any) = key.asInstanceOf[Int]
    }
    // partitionBy so we have a narrow dependency 有一个窄依赖
    val a = sc.parallelize(Array((1, "a"), (2, "b"), (3, "c"))).partitionBy(p)
    // more partitions/no partitioner so a shuffle dependency
    //更多分区/没有分区Shuffle依赖
    val b = sc.parallelize(Array((2, "b"), (3, "cc"), (4, "d")), 4)
    //返回在RDD中出现,并且不在otherRDD中出现的元素,不去重
    val c = a.subtract(b)
    assert(c.collect().toSet === Set((1, "a"), (3, "c")))//Key和value必须相同才能相减
    // Ideally we could keep the original partitioner...
    //最理想的是我们能保持原有的分区…
    assert(c.partitioner === None)
  }

  test("subtractByKey") {
    val a = sc.parallelize(Array((1, "a"), (1, "a"), (2, "b"), (3, "c")), 2)
    val b = sc.parallelize(Array((2, 20), (3, 30), (4, 40)), 4)
    //返回在RDD中出现的Key元素,并且不在otherRDD中出现的Key元素,不去重
    val c = a.subtractByKey(b)
    assert(c.collect().toSet === Set((1, "a"), (1, "a")))
    assert(c.partitions.size === a.partitions.size)
  }

  test("subtractByKey with narrow dependency") { //subtractByKey 窄依赖
    // use a deterministic partitioner 使用一个确定性的分割
    val p = new Partitioner() {
      def numPartitions = 5
      def getPartition(key: Any) = key.asInstanceOf[Int]
    }
    // partitionBy so we have a narrow dependency
    val a = sc.parallelize(Array((1, "a"), (1, "a"), (2, "b"), (3, "c"))).partitionBy(p)
    // more partitions/no partitioner so a shuffle dependency
    val b = sc.parallelize(Array((2, "b"), (3, "cc"), (4, "d")), 4)
    //返回在RDD中出现的Key元素,并且不在otherRDD中出现的Key元素,不去重
    val c = a.subtractByKey(b)
    assert(c.collect().toSet === Set((1, "a"), (1, "a")))
    assert(c.partitioner.get === p)
  }

  test("foldByKey") {
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    //合并每一个 key的所有value值,初始值为0
    val sums = pairs.foldByKey(0)(_ + _).collect()
    assert(sums.toSet === Set((1, 7), (2, 1)))
  }

  test("foldByKey with mutable result type") {//可变的结果类型
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (1, 1), (2, 1)))
    val bufs = pairs.mapValues(v => ArrayBuffer(v)).cache()
    // Fold the values using in-place mutation
    // 使用位置突变折叠值
    val sums = bufs.foldByKey(new ArrayBuffer[Int])(_ ++= _).collect()
    assert(sums.toSet === Set((1, ArrayBuffer(1, 2, 3, 1)), (2, ArrayBuffer(1))))
    // Check that the mutable objects in the original RDD were not changed
    // 检查在原RDD的可变对象没有改变
    assert(bufs.collect().toSet === Set(
      (1, ArrayBuffer(1)),
      (1, ArrayBuffer(2)),
      (1, ArrayBuffer(3)),
      (1, ArrayBuffer(1)),
      (2, ArrayBuffer(1))))
  }

  test("saveNewAPIHadoopFile should call setConf if format is configurable") {
    val pairs = sc.parallelize(Array((new Integer(1), new Integer(1))))

    // No error, non-configurable formats still work
    //没有错误,非可配置格式仍然工作
    pairs.saveAsNewAPIHadoopFile[NewFakeFormat]("ignored")

    /*
      Check that configurable formats get configured:
       检查可配置的格式配置：
       如果我们尝试写，ConfigTestFormat会抛出异常
       当setConf尚未被首先调用时。
       断言在ConfigTestFormat.getRecordWriter中。
      ConfigTestFormat throws an exception if we try to write
      to it when setConf hasn't been called first.
      Assertion is in ConfigTestFormat.getRecordWriter.
     */
    pairs.saveAsNewAPIHadoopFile[ConfigTestFormat]("ignored")
  }

  test("saveAsHadoopFile should respect configured output committers") {//应该尊重配置输出
    val pairs = sc.parallelize(Array((new Integer(1), new Integer(1))))
    val conf = new JobConf()
    conf.setOutputCommitter(classOf[FakeOutputCommitter])

    FakeOutputCommitter.ran = false
    pairs.saveAsHadoopFile(
      "ignored", pairs.keyClass, pairs.valueClass, classOf[FakeOutputFormat], conf)

    assert(FakeOutputCommitter.ran, "OutputCommitter was never called")
  }

  test("lookup") { //lookup 返回指定Key对应的元素形成的Seq
    val pairs = sc.parallelize(Array((1, 2), (3, 4), (5, 6), (5, 7)))

    assert(pairs.partitioner === None)
    //返回指定Key对应的元素形成的Seq
    assert(pairs.lookup(1) === Seq(2))
    assert(pairs.lookup(5) === Seq(6, 7))
    assert(pairs.lookup(-1) === Seq())

  }

  test("lookup with partitioner") { //查找与分区
    val pairs = sc.parallelize(Array((1, 2), (3, 4), (5, 6), (5, 7)))

    val p = new Partitioner {
      def numPartitions: Int = 2

      def getPartition(key: Any): Int = Math.abs(key.hashCode() % 2)
    }
    val shuffled = pairs.partitionBy(p)

    assert(shuffled.partitioner === Some(p))
    assert(shuffled.lookup(1) === Seq(2))
    assert(shuffled.lookup(5) === Seq(6, 7))
    assert(shuffled.lookup(-1) === Seq())
  }

  test("lookup with bad partitioner") { //查找坏的分区
    val pairs = sc.parallelize(Array((1, 2), (3, 4), (5, 6), (5, 7)))

    val p = new Partitioner {
      def numPartitions: Int = 2

      def getPartition(key: Any): Int = key.hashCode() % 2
    }
    val shuffled = pairs.partitionBy(p)

    assert(shuffled.partitioner === Some(p))
    assert(shuffled.lookup(1) === Seq(2))
    intercept[IllegalArgumentException] { shuffled.lookup(-1) }
  }

  private object StratifiedAuxiliary {
    def stratifier(fractionPositive: Double): (Int) => String = {
      (x: Int) => if (x % 10 < (10 * fractionPositive).toInt) "1" else "0"
    }

    def checkSize(exact: Boolean,
      withReplacement: Boolean,
      expected: Long,
      actual: Long,
      p: Double): Boolean = {
      if (exact) {
        return expected == actual
      }
      val stdev = if (withReplacement) math.sqrt(expected) else math.sqrt(expected * p * (1 - p))
      // Very forgiving margin since we're dealing with very small sample sizes most of the time
      //非常宽容，因为我们在大多数时间处理非常小的样本大小
      math.abs(actual - expected) <= 6 * stdev
    }

    def testSampleExact(stratifiedData: RDD[(String, Int)],
      samplingRate: Double,
      seed: Long,
      n: Long): Unit = {
      testBernoulli(stratifiedData, true, samplingRate, seed, n)
      testPoisson(stratifiedData, true, samplingRate, seed, n)
    }

    def testSample(stratifiedData: RDD[(String, Int)],
      samplingRate: Double,
      seed: Long,
      n: Long): Unit = {
      testBernoulli(stratifiedData, false, samplingRate, seed, n)
      testPoisson(stratifiedData, false, samplingRate, seed, n)
    }

    // Without replacement validation
    //无需更换验证
    def testBernoulli(stratifiedData: RDD[(String, Int)],
      exact: Boolean,
      samplingRate: Double,
      seed: Long,
      n: Long): Unit = {
      val expectedSampleSize = stratifiedData.countByKey()
        .mapValues(count => math.ceil(count * samplingRate).toInt)
      val fractions = Map("1" -> samplingRate, "0" -> samplingRate)
      val sample = if (exact) {
        stratifiedData.sampleByKeyExact(false, fractions, seed)
      } else {
        stratifiedData.sampleByKey(false, fractions, seed)
      }
      val sampleCounts = sample.countByKey()
      val takeSample = sample.collect()
      sampleCounts.foreach {
        case (k, v) =>
          assert(checkSize(exact, false, expectedSampleSize(k), v, samplingRate))
      }
      assert(takeSample.size === takeSample.toSet.size)
      takeSample.foreach { x => assert(1 <= x._2 && x._2 <= n, s"elements not in [1, $n]") }
    }

    // With replacement validation 替换的验证
    def testPoisson(stratifiedData: RDD[(String, Int)],
      exact: Boolean,
      samplingRate: Double,
      seed: Long,
      n: Long): Unit = {
      val expectedSampleSize = stratifiedData.countByKey().mapValues(count =>
        math.ceil(count * samplingRate).toInt)
      val fractions = Map("1" -> samplingRate, "0" -> samplingRate)
      val sample = if (exact) {
        stratifiedData.sampleByKeyExact(true, fractions, seed)
      } else {
        stratifiedData.sampleByKey(true, fractions, seed)
      }
      val sampleCounts = sample.countByKey()
      val takeSample = sample.collect()
      sampleCounts.foreach {
        case (k, v) =>
          assert(checkSize(exact, true, expectedSampleSize(k), v, samplingRate))
      }
      val groupedByKey = takeSample.groupBy(_._1)
      for ((key, v) <- groupedByKey) {
        if (expectedSampleSize(key) >= 100 && samplingRate >= 0.1) {
          // sample large enough for there to be repeats with high likelihood
          //样本足够大，可能会重复，可能性很高
          assert(v.toSet.size < expectedSampleSize(key))
        } else {
          if (exact) {
            assert(v.toSet.size <= expectedSampleSize(key))
          } else {
            assert(checkSize(false, true, expectedSampleSize(key), v.toSet.size, samplingRate))
          }
        }
      }
      takeSample.foreach(x => assert(1 <= x._2 && x._2 <= n, s"elements not in [1, $n]"))
    }
  }

}

/*
  These classes are fakes for testing 这些类是假的测试
    "saveNewAPIHadoopFile should call setConf if format is configurable".
    如果格式是可配置的，saveNewAPIHadoopFile应该调用setConf
  Unfortunately, they have to be top level classes, and not defined in
  the test method, because otherwise Scala won't generate no-args constructors
  and the test will therefore throw InstantiationException when saveAsNewAPIHadoopFile
  tries to instantiate them with Class.newInstance.

  不幸的是,它们必须是顶级类,并且没有在测试方法中定义,因为否则Scala将不会生成no-args构造函数,
  并且当saveAsNewAPIHadoopFile尝试使用Class.newInstance实例化它时,测试将抛出InstantiationException
 */

/*
 * Original Hadoop API
 */
class FakeWriter extends RecordWriter[Integer, Integer] {
  override def write(key: Integer, value: Integer): Unit = ()

  override def close(reporter: Reporter): Unit = ()
}

class FakeOutputCommitter() extends OutputCommitter() {
  override def setupJob(jobContext: JobContext): Unit = ()

  override def needsTaskCommit(taskContext: TaskAttemptContext): Boolean = true

  override def setupTask(taskContext: TaskAttemptContext): Unit = ()

  override def commitTask(taskContext: TaskAttemptContext): Unit = {
    FakeOutputCommitter.ran = true
    ()
  }

  override def abortTask(taskContext: TaskAttemptContext): Unit = ()
}

/*
 * Used to communicate state between the test harness and the OutputCommitter.
 * 用于在测试线束和OutputCommitter之间通信状态
 */
object FakeOutputCommitter {
  var ran = false
}

class FakeOutputFormat() extends OutputFormat[Integer, Integer]() {
  override def getRecordWriter(
    ignored: FileSystem,
    job: JobConf, name: String,
    progress: Progressable): RecordWriter[Integer, Integer] = {
    new FakeWriter()
  }

  override def checkOutputSpecs(ignored: FileSystem, job: JobConf): Unit = ()
}

/*
 * New-style Hadoop API
 */
class NewFakeWriter extends NewRecordWriter[Integer, Integer] {

  def close(p1: NewTaskAttempContext): Unit = ()

  def write(p1: Integer, p2: Integer): Unit = ()

}

class NewFakeCommitter extends NewOutputCommitter {
  def setupJob(p1: NewJobContext): Unit = ()

  def needsTaskCommit(p1: NewTaskAttempContext): Boolean = false

  def setupTask(p1: NewTaskAttempContext): Unit = ()

  def commitTask(p1: NewTaskAttempContext): Unit = ()

  def abortTask(p1: NewTaskAttempContext): Unit = ()
}

class NewFakeFormat() extends NewOutputFormat[Integer, Integer]() {

  def checkOutputSpecs(p1: NewJobContext): Unit = ()

  def getRecordWriter(p1: NewTaskAttempContext): NewRecordWriter[Integer, Integer] = {
    new NewFakeWriter()
  }

  def getOutputCommitter(p1: NewTaskAttempContext): NewOutputCommitter = {
    new NewFakeCommitter()
  }
}

class ConfigTestFormat() extends NewFakeFormat() with Configurable {

  var setConfCalled = false
  def setConf(p1: Configuration): Unit = {
    setConfCalled = true
    ()
  }

  def getConf: Configuration = null

  override def getRecordWriter(p1: NewTaskAttempContext): NewRecordWriter[Integer, Integer] = {
    assert(setConfCalled, "setConf was never called")
    super.getRecordWriter(p1)
  }
}
