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

package org.apache.spark

import java.io.File

import scala.reflect.ClassTag

import org.apache.spark.rdd._
import org.apache.spark.storage.{BlockId, StorageLevel, TestBlockId}
import org.apache.spark.util.Utils

/**
 * Test suite for end-to-end checkpointing functionality.
 * 端到端的检查点功能测试套件
 * This tests both reliable(可靠) checkpoints and local checkpoints.
 * 这两个测试都可靠的检查点和本地检查点
 */
class CheckpointSuite extends SparkFunSuite with LocalSparkContext with Logging {
  private var checkpointDir: File = _
  private val partitioner = new HashPartitioner(2)

  override def beforeEach(): Unit = {
    super.beforeEach()
    //创建检查点目录包括删除文件
    checkpointDir = File.createTempFile("temp", "", Utils.createTempDir())
    checkpointDir.delete()//删除临时文件,目录不删除
    sc = new SparkContext("local", "test")
    sc.setCheckpointDir(checkpointDir.toString)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    //递归删除检查点文件
    Utils.deleteRecursively(checkpointDir)
  }
  //基本的检查点
  runTest("basic checkpointing") { reliableCheckpoint: Boolean =>
    //科里化函数
    val parCollection = sc.makeRDD(1 to 4)
    val flatMappedRDD = parCollection.flatMap(x => 1 to x)
    checkpoint(flatMappedRDD, reliableCheckpoint)//设置检查点
    //窄依赖
    assert(flatMappedRDD.dependencies.head.rdd === parCollection)
    val result = flatMappedRDD.collect()
    //执行动作后rdd不相等
    assert(flatMappedRDD.dependencies.head.rdd != parCollection)
    assert(flatMappedRDD.collect() === result)
  }
  //RDD窄依赖  
  runTest("RDDs with one-to-one dependencies") { reliableCheckpoint: Boolean =>
    testRDD(_.map(x => x.toString), reliableCheckpoint)
    testRDD(_.flatMap(x => 1 to x), reliableCheckpoint)
    testRDD(_.filter(_ % 2 == 0), reliableCheckpoint)
    testRDD(_.sample(false, 0.5, 0), reliableCheckpoint)
    testRDD(_.glom(), reliableCheckpoint)
    testRDD(_.mapPartitions(_.map(_.toString)), reliableCheckpoint)
    testRDD(_.map(x => (x % 2, 1)).reduceByKey(_ + _).mapValues(_.toString), reliableCheckpoint)
    testRDD(_.map(x => (x % 2, 1)).reduceByKey(_ + _).flatMapValues(x => 1 to x),
      reliableCheckpoint)
    testRDD(_.pipe(Seq("cat")), reliableCheckpoint)
  }
  //RDD的平行处理
  runTest("ParallelCollectionRDD") { reliableCheckpoint: Boolean =>
    val parCollection = sc.makeRDD(1 to 4, 2)
    val numPartitions = parCollection.partitions.size//分区大小
    checkpoint(parCollection, reliableCheckpoint)
    assert(parCollection.dependencies === Nil)//依赖为空
    val result = parCollection.collect()
    if (reliableCheckpoint) {//分布式检查点
      //file:/C:/Users/liushuhua/AppData/Local/Temp/spark-8af57557-baeb-40cd-a56d-aceb21ff04c0/temp2755421388348795500/787781ef-805e-4869-adfa-0fe4eea1473a/rdd-0
      val chFile=parCollection.getCheckpointFile.get
      //ReliableCheckpointRDD
      val checkpoint=sc.checkpointFile[Int](chFile)
      assert(checkpoint.collect() === result)
    }

    assert(parCollection.dependencies != Nil)//Nil代表空列表
    assert(parCollection.partitions.length === numPartitions)
    assert(parCollection.partitions.toList ===
      parCollection.checkpointData.get.getPartitions.toList)
    assert(parCollection.collect() === result)
  }

  runTest("BlockRDD") { reliableCheckpoint: Boolean =>
    val blockId = TestBlockId("id")
    val blockManager = SparkEnv.get.blockManager
    blockManager.putSingle(blockId, "test", StorageLevel.MEMORY_ONLY)
    val blockRDD = new BlockRDD[String](sc, Array(blockId))
    val numPartitions = blockRDD.partitions.size
    checkpoint(blockRDD, reliableCheckpoint)
    val result = blockRDD.collect()
    if (reliableCheckpoint) {
      assert(sc.checkpointFile[String](blockRDD.getCheckpointFile.get).collect() === result)
    }
    assert(blockRDD.dependencies != Nil)
    assert(blockRDD.partitions.length === numPartitions)
    assert(blockRDD.partitions.toList === blockRDD.checkpointData.get.getPartitions.toList)
    assert(blockRDD.collect() === result)
  }

  runTest("ShuffleRDD") { reliableCheckpoint: Boolean =>
    testRDD(rdd => {
      // Creating ShuffledRDD directly as PairRDDFunctions.combineByKey produces a MapPartitionedRDD
      //直接创建ShuffledRDD作为PairRDDFunctions.combineByKey生成一个MapPartitionedRDD
      new ShuffledRDD[Int, Int, Int](rdd.map(x => (x % 2, 1)), partitioner)
    }, reliableCheckpoint)
  }

  runTest("UnionRDD") { reliableCheckpoint: Boolean =>
    def otherRDD: RDD[Int] = sc.makeRDD(1 to 10, 1)
    testRDD(_.union(otherRDD), reliableCheckpoint)
    testRDDPartitions(_.union(otherRDD), reliableCheckpoint)
  }
  //笛卡尔RDD
  runTest("CartesianRDD") { reliableCheckpoint: Boolean =>
    def otherRDD: RDD[Int] = sc.makeRDD(1 to 10, 1)
    testRDD(new CartesianRDD(sc, _, otherRDD), reliableCheckpoint)
    testRDDPartitions(new CartesianRDD(sc, _, otherRDD), reliableCheckpoint)

    // Test that the CartesianRDD updates parent partitions (CartesianRDD.s1/s2) after
    //测试CartesianRDD之后更新父分区（CartesianRDD.s1 / s2）父RDD已经被检查点，并且父分区已经被改变。
    //请注意，此测试对于CartesianRDD的当前实现非常具体。
    // the parent RDD has been checkpointed and parent partitions have been changed.
    // Note that this test is very specific to the current implementation of CartesianRDD.
    val ones = sc.makeRDD(1 to 100, 10).map(x => x)
    checkpoint(ones, reliableCheckpoint) // checkpoint that MappedRDD
    val cartesian = new CartesianRDD(sc, ones, ones)
    val splitBeforeCheckpoint =
      serializeDeserialize(cartesian.partitions.head.asInstanceOf[CartesianPartition])
    cartesian.count() // do the checkpointing
    val splitAfterCheckpoint =
      serializeDeserialize(cartesian.partitions.head.asInstanceOf[CartesianPartition])
    assert(
      (splitAfterCheckpoint.s1.getClass != splitBeforeCheckpoint.s1.getClass) &&
        (splitAfterCheckpoint.s2.getClass != splitBeforeCheckpoint.s2.getClass),
      "CartesianRDD.s1 and CartesianRDD.s2 not updated after parent RDD is checkpointed"
    )
  }
  //合并RDD
  runTest("CoalescedRDD") { reliableCheckpoint: Boolean =>
    testRDD(_.coalesce(2), reliableCheckpoint)
    testRDDPartitions(_.coalesce(2), reliableCheckpoint)

    // Test that the CoalescedRDDPartition updates parent partitions (CoalescedRDDPartition.parents)
    // after the parent RDD has been checkpointed and parent partitions have been changed.
    // Note that this test is very specific to the current implementation of
    // CoalescedRDDPartitions.
    val ones = sc.makeRDD(1 to 100, 10).map(x => x)
    checkpoint(ones, reliableCheckpoint) // checkpoint that MappedRDD
    val coalesced = new CoalescedRDD(ones, 2)
    val splitBeforeCheckpoint =
      serializeDeserialize(coalesced.partitions.head.asInstanceOf[CoalescedRDDPartition])
    coalesced.count() // do the checkpointing
    val splitAfterCheckpoint =
      serializeDeserialize(coalesced.partitions.head.asInstanceOf[CoalescedRDDPartition])
    assert(
      splitAfterCheckpoint.parents.head.getClass != splitBeforeCheckpoint.parents.head.getClass,
      "CoalescedRDDPartition.parents not updated after parent RDD is checkpointed"
    )
  }
  //分组RDD
  runTest("CoGroupedRDD") { reliableCheckpoint: Boolean =>
    val longLineageRDD1 = generateFatPairRDD()

    // Collect the RDD as sequences instead of arrays to enable equality tests in testRDD
    //收集RDD作为序列而不是数组，以启用testRDD中的相等性测试
    val seqCollectFunc = (rdd: RDD[(Int, Array[Iterable[Int]])]) =>
      rdd.map{case (p, a) => (p, a.toSeq)}.collect(): Any

    /*testRDD(rdd => {
      CheckpointSuite.cogroup(longLineageRDD1, rdd.map(x => (x % 2, 1)), partitioner)
    }, reliableCheckpoint, seqCollectFunc)

    val longLineageRDD2 = generateFatPairRDD()
    testRDDPartitions(rdd => {
      CheckpointSuite.cogroup(
        longLineageRDD2, sc.makeRDD(1 to 2, 2).map(x => (x % 2, 1)), partitioner)
    }, reliableCheckpoint, seqCollectFunc)*/
  }
  //拉链分区
  runTest("ZippedPartitionsRDD") { reliableCheckpoint: Boolean =>
    testRDD(rdd => rdd.zip(rdd.map(x => x)), reliableCheckpoint)
    testRDDPartitions(rdd => rdd.zip(rdd.map(x => x)), reliableCheckpoint)

    // Test that ZippedPartitionsRDD updates parent partitions after parent RDDs have
    // been checkpointed and parent partitions have been changed.
    // Note that this test is very specific to the implementation of ZippedPartitionsRDD.
    val rdd = generateFatRDD()
    val zippedRDD = rdd.zip(rdd.map(x => x)).asInstanceOf[ZippedPartitionsRDD2[_, _, _]]
    checkpoint(zippedRDD.rdd1, reliableCheckpoint)
    checkpoint(zippedRDD.rdd2, reliableCheckpoint)
    val partitionBeforeCheckpoint =
      serializeDeserialize(zippedRDD.partitions.head.asInstanceOf[ZippedPartitionsPartition])
    zippedRDD.count()
    val partitionAfterCheckpoint =
      serializeDeserialize(zippedRDD.partitions.head.asInstanceOf[ZippedPartitionsPartition])
    assert(
      partitionAfterCheckpoint.partitions(0).getClass !=
        partitionBeforeCheckpoint.partitions(0).getClass &&
      partitionAfterCheckpoint.partitions(1).getClass !=
        partitionBeforeCheckpoint.partitions(1).getClass,
      "ZippedPartitionsRDD partition 0 (or 1) not updated after parent RDDs are checkpointed"
    )
  }
  //分区意识联合RDD
  runTest("PartitionerAwareUnionRDD") { reliableCheckpoint: Boolean =>
    testRDD(rdd => {
      new PartitionerAwareUnionRDD[(Int, Int)](sc, Array(
        generateFatPairRDD(),
        rdd.map(x => (x % 2, 1)).reduceByKey(partitioner, _ + _)
      ))
    }, reliableCheckpoint)

    testRDDPartitions(rdd => {
      new PartitionerAwareUnionRDD[(Int, Int)](sc, Array(
        generateFatPairRDD(),
        rdd.map(x => (x % 2, 1)).reduceByKey(partitioner, _ + _)
      ))
    }, reliableCheckpoint)

    // Test that the PartitionerAwareUnionRDD updates parent partitions
    //测试partitionerawareunionrdd更新父分区
    // (PartitionerAwareUnionRDD.parents) after the parent RDD has been checkpointed and parent
    // partitions have been changed. Note that this test is very specific to the current
    // implementation of PartitionerAwareUnionRDD.
    val pairRDD = generateFatPairRDD()
    checkpoint(pairRDD, reliableCheckpoint)
    val unionRDD = new PartitionerAwareUnionRDD(sc, Array(pairRDD))
    val partitionBeforeCheckpoint = serializeDeserialize(
      unionRDD.partitions.head.asInstanceOf[PartitionerAwareUnionRDDPartition])
    pairRDD.count()
    val partitionAfterCheckpoint = serializeDeserialize(
      unionRDD.partitions.head.asInstanceOf[PartitionerAwareUnionRDDPartition])
    assert(
      partitionBeforeCheckpoint.parents.head.getClass !=
        partitionAfterCheckpoint.parents.head.getClass,
      "PartitionerAwareUnionRDDPartition.parents not updated after parent RDD is checkpointed"
    )
  }

  runTest("CheckpointRDD with zero partitions") { reliableCheckpoint: Boolean => //自身类型
    val rdd = new BlockRDD[Int](sc, Array[BlockId]())
    assert(rdd.partitions.size === 0)
    assert(rdd.isCheckpointed === false)
    assert(rdd.isCheckpointedAndMaterialized === false)
    checkpoint(rdd, reliableCheckpoint)
    assert(rdd.isCheckpointed === false)
    assert(rdd.isCheckpointedAndMaterialized === false)
    assert(rdd.count() === 0)
    assert(rdd.isCheckpointed === true)
    assert(rdd.isCheckpointedAndMaterialized === true)
    assert(rdd.partitions.size === 0)
  }

  // Utility test methods

  /** 
   *  Checkpoint the RDD either locally or reliably.
   *  检查站的RDD或者本地的可靠 
   *  */
  private def checkpoint(rdd: RDD[_], reliableCheckpoint: Boolean): Unit = {
    if (reliableCheckpoint) {
      rdd.checkpoint()//分布式检查点
    } else {
      rdd.localCheckpoint() //本地检查点
    }
  }

  /** 
   *  Run a test twice, once for local checkpointing and once for reliable checkpointing. 
   *  运行测试两次,一次本地检查点和一次可靠性检查点
   *  */
  private def runTest(name: String)(body: Boolean => Unit): Unit = {//科里化函数
    test(name + " [reliable checkpoint]")(body(true))
    test(name + " [local checkpoint]")(body(false))
  }

  private def defaultCollectFunc[T](rdd: RDD[T]): Any = rdd.collect()

  /**
   * Test checkpointing of the RDD generated by the given operation. It tests whether the
   * serialized size of the RDD is reduce after checkpointing or not. This function should be called
   * on all RDDs that have a parent RDD (i.e., do not call on ParallelCollection, BlockRDD, etc.).
   * 测试由给定操作生成的RDD的检查点,它检查在检查点之后RDD的序列化大小是否减少,
    * 应该对所有具有父RDD的RDD(即不调用ParallelCollection,BlockRDD等)调用此函数
   * @param op an operation to run on the RDD 在RDD上运行的操作
   * @param reliableCheckpoint if true, use reliable checkpoints, otherwise use local checkpoints
    *                           如果为true,请使用可靠的检查点,否则使用本地检查点
   * @param collectFunc a function for collecting the values in the RDD, in case there are
   *   non-comparable types like arrays that we want to convert to something that supports ==
    *   用于收集RDD中的值的函数,以防存在不可比较的类型,如我们要转换为支持的数组的数组
   */
  private def testRDD[U: ClassTag](
      op: (RDD[Int]) => RDD[U],
      reliableCheckpoint: Boolean,
      collectFunc: RDD[U] => Any = defaultCollectFunc[U] _): Unit = {
    // Generate the final RDD using given RDD operation
    //使用RDD操作生成最终的RDD
    val baseRDD = generateFatRDD()
    val operatedRDD = op(baseRDD)
    val parentRDD = operatedRDD.dependencies.headOption.orNull
    val rddType = operatedRDD.getClass.getSimpleName
    val numPartitions = operatedRDD.partitions.length

    // Force initialization of all the data structures in RDDs
    //在RDDs,所有的数据结构力的初始化
    // Without this, serializing the RDD will give a wrong estimate of the size of the RDD
    //将序列化RDD的RDD大小的错误估计
    initializeRdd(operatedRDD)

    val partitionsBeforeCheckpoint = operatedRDD.partitions

    // Find serialized sizes before and after the checkpoint
    //查找序列化之前和之后的检查点大小
    logInfo("RDD after checkpoint: " + operatedRDD + "\n" + operatedRDD.toDebugString)
    val (rddSizeBeforeCheckpoint, partitionSizeBeforeCheckpoint) = getSerializedSizes(operatedRDD)
    checkpoint(operatedRDD, reliableCheckpoint)
    val result = collectFunc(operatedRDD)
    operatedRDD.collect() // force re-initialization of post-checkpoint lazy variables
    val (rddSizeAfterCheckpoint, partitionSizeAfterCheckpoint) = getSerializedSizes(operatedRDD)
    logInfo("RDD after checkpoint: " + operatedRDD + "\n" + operatedRDD.toDebugString)

    // Test whether the checkpoint file has been created
    //测试是否已创建检查点文件
    if (reliableCheckpoint) {
      assert(collectFunc(sc.checkpointFile[U](operatedRDD.getCheckpointFile.get)) === result)
    }

    // Test whether dependencies have been changed from its earlier parent RDD
    //测试是否依赖之前的父RDD
    assert(operatedRDD.dependencies.head.rdd != parentRDD)

    // Test whether the partitions have been changed from its earlier partitions
    //测试分区是否已从之前的分区更改过了
    assert(operatedRDD.partitions.toList != partitionsBeforeCheckpoint.toList)

    // Test whether the partitions have been changed to the new Hadoop partitions
    //测试是否分区已改为新的Hadoop分区
    assert(operatedRDD.partitions.toList === operatedRDD.checkpointData.get.getPartitions.toList)

    // Test whether the number of partitions is same as before
    //测试分区的数量是否和以前相同
    assert(operatedRDD.partitions.length === numPartitions)

    // Test whether the data in the checkpointed RDD is same as original
    //测试是否在检查点RDD数据与原来数相同
    assert(collectFunc(operatedRDD) === result)

    // Test whether serialized size of the RDD has reduced.
    //测试是否序列化大小的RDD减少
    logInfo("Size of " + rddType +
      " [" + rddSizeBeforeCheckpoint + " --> " + rddSizeAfterCheckpoint + "]")
    assert(
      rddSizeAfterCheckpoint < rddSizeBeforeCheckpoint,
      "Size of " + rddType + " did not reduce after checkpointing " +
        " [" + rddSizeBeforeCheckpoint + " --> " + rddSizeAfterCheckpoint + "]"
    )
  }

  /**
   * Test whether checkpointing of the parent of the generated RDD also
   * truncates the lineage or not. Some RDDs like CoGroupedRDD hold on to its parent
   * RDDs partitions. So even if the parent RDD is checkpointed and its partitions changed,
   * the generated RDD will remember the partitions and therefore potentially the whole lineage.
   * This function should be called only those RDD whose partitions refer to parent RDD's
   * partitions (i.e., do not call it on simple RDD like MappedRDD).
    *
    *
    * 测试生成的RDD的父节点的检查点是否也截断谱系。一些RDD(如CoGroupedRDD)保留在其父RDD分区上。
    * 因此,即使父RDD是检查点,并且其分区更改，
    * 生成的RDD将记住分区,从而潜在地记录整个谱系,这个函数只应该被调用那些分区指向父RDD的RDD
    * 分区(即不要像简单的RDD那样调用MappedRDD）
   *
   * @param op an operation to run on the RDD 在RDD上运行的操作
   * @param reliableCheckpoint if true, use reliable checkpoints, otherwise use local checkpoints
    *                           如果为true,请使用可靠的检查点,否则使用本地检查点
   * @param collectFunc a function for collecting the values in the RDD, in case there are
   *   non-comparable types like arrays that we want to convert to something that supports ==
    *   用于收集RDD中的值的函数,以防存在不可比较的类型,如我们要转换为支持的数组的数组
   */
  private def testRDDPartitions[U: ClassTag](
      op: (RDD[Int]) => RDD[U],
      reliableCheckpoint: Boolean,
      collectFunc: RDD[U] => Any = defaultCollectFunc[U] _): Unit = {
    // Generate the final RDD using given RDD operation
    val baseRDD = generateFatRDD()
    val operatedRDD = op(baseRDD)
    val parentRDDs = operatedRDD.dependencies.map(_.rdd)
    val rddType = operatedRDD.getClass.getSimpleName

    // Force initialization of all the data structures in RDDs
    // Without this, serializing the RDD will give a wrong estimate of the size of the RDD
    initializeRdd(operatedRDD)

    // Find serialized sizes before and after the checkpoint
    //查找检查点前后的序列化大小
    logInfo("RDD after checkpoint: " + operatedRDD + "\n" + operatedRDD.toDebugString)
    val (rddSizeBeforeCheckpoint, partitionSizeBeforeCheckpoint) = getSerializedSizes(operatedRDD)
    // checkpoint the parent RDD, not the generated one
    //检查点父级RDD，而不是生成的RDD
    parentRDDs.foreach { rdd =>
      checkpoint(rdd, reliableCheckpoint)
    }
    val result = collectFunc(operatedRDD)  // force checkpointing
    operatedRDD.collect() // force re-initialization of post-checkpoint lazy variables
    val (rddSizeAfterCheckpoint, partitionSizeAfterCheckpoint) = getSerializedSizes(operatedRDD)
    logInfo("RDD after checkpoint: " + operatedRDD + "\n" + operatedRDD.toDebugString)

    // Test whether the data in the checkpointed RDD is same as original
    // 检查检查点RDD中的数据是否与原始数据相同
    assert(collectFunc(operatedRDD) === result)

    // Test whether serialized size of the partitions has reduced
    // 测试分区的序列化大小是否减少
    logInfo("Size of partitions of " + rddType +
      " [" + partitionSizeBeforeCheckpoint + " --> " + partitionSizeAfterCheckpoint + "]")
    assert(
      partitionSizeAfterCheckpoint < partitionSizeBeforeCheckpoint,
      "Size of " + rddType + " partitions did not reduce after checkpointing parent RDDs" +
        " [" + partitionSizeBeforeCheckpoint + " --> " + partitionSizeAfterCheckpoint + "]"
    )
  }

  /**
   * Generate an RDD such that both the RDD and its partitions have large size.
   * 生成一个RDD,有分区大小
   */
  private def generateFatRDD(): RDD[Int] = {
    new FatRDD(sc.makeRDD(1 to 100, 4)).map(x => x)
  }

  /**
   * Generate an pair RDD (with partitioner) such that both the RDD and its partitions
   * 产生一个对RDD(和分区),有分区大小
   * have large size.
   */
  private def generateFatPairRDD(): RDD[(Int, Int)] = {
    new FatPairRDD(sc.makeRDD(1 to 100, 4), partitioner).mapValues(x => x)
  }

  /**
   * Get serialized sizes of the RDD and its partitions, in order to test whether the size shrinks
   * 得到序列化大小的RDD分区,为了测试是否大小在检查点,忽略检查点数据字段,可能当我们成长的检查点
   * upon checkpointing. Ignores the checkpointData field, which may grow when we checkpoint.
   */
  private def getSerializedSizes(rdd: RDD[_]): (Int, Int) = {
    val rddSize = Utils.serialize(rdd).size
    val rddCpDataSize = Utils.serialize(rdd.checkpointData).size
    val rddPartitionSize = Utils.serialize(rdd.partitions).size
    val rddDependenciesSize = Utils.serialize(rdd.dependencies).size

    // Print detailed size, helps in debugging
    logInfo("Serialized sizes of " + rdd +
      ": RDD = " + rddSize +
      ", RDD checkpoint data = " + rddCpDataSize +
      ", RDD partitions = " + rddPartitionSize +
      ", RDD dependencies = " + rddDependenciesSize
    )
    // this makes sure that serializing the RDD's checkpoint data does not
    // serialize the whole RDD as well
    assert(
      rddSize > rddCpDataSize,
      "RDD's checkpoint data (" + rddCpDataSize  + ") is equal or larger than the " +
        "whole RDD with checkpoint data (" + rddSize + ")"
    )
    (rddSize - rddCpDataSize, rddPartitionSize)
  }

  /**
   * Serialize and deserialize an object. This is useful to verify the objects
   * contents after deserialization (e.g., the contents of an RDD split after
   * it is sent to a slave along with a task)
    * 序列化和反序列化对象,这对验证对象非常有用反序列化后的内容(例如，RDD之后的内容一个任务发送到一个奴隶)
   */
  private def serializeDeserialize[T](obj: T): T = {
    val bytes = Utils.serialize(obj)//序列化
    Utils.deserialize[T](bytes)//反序列化
  }

  /**
   * Recursively(递归) force the initialization of the all members of an RDD and it parents.
    * 递归强制RDD的所有成员及其父RDD的初始化
   */
  private def initializeRdd(rdd: RDD[_]): Unit = {
    rdd.partitions // forces the
    rdd.dependencies.map(_.rdd).foreach(initializeRdd)
  }

}

/** 
 *  RDD partition that has large serialized size.
 *  RDD的分区大小较大的序列化
 *   */
class FatPartition(val partition: Partition) extends Partition {
  val bigData = new Array[Byte](10000)
  def index: Int = partition.index
}

/** 
 *  RDD that has large serialized size.
 *  具有大序列化大小的RDD
 *  */
class FatRDD(parent: RDD[Int]) extends RDD[Int](parent) {
  val bigData = new Array[Byte](100000)

  protected def getPartitions: Array[Partition] = {
    parent.partitions.map(p => new FatPartition(p))
  }

  def compute(split: Partition, context: TaskContext): Iterator[Int] = {
    parent.compute(split.asInstanceOf[FatPartition].partition, context)
  }
}

/**
  *  Pair RDD that has large serialized size.
  *  对RDD具有较大的序列化大小
  * */
class FatPairRDD(parent: RDD[Int], _partitioner: Partitioner) extends RDD[(Int, Int)](parent) {
  val bigData = new Array[Byte](100000)

  protected def getPartitions: Array[Partition] = {
    parent.partitions.map(p => new FatPartition(p))
  }

  @transient override val partitioner = Some(_partitioner)

  def compute(split: Partition, context: TaskContext): Iterator[(Int, Int)] = {
    parent.compute(split.asInstanceOf[FatPartition].partition, context).map(x => (x, x))
  }
}

object CheckpointSuite {
  // This is a custom cogroup function that does not use mapValues like
  //这是一个不使用mapValues的自定义cogroup函数
  // the PairRDDFunctions.cogroup()
  def cogroup[K, V](first: RDD[(K, V)], second: RDD[(K, V)], part: Partitioner)
    : RDD[(K, Array[Iterable[V]])] = {
    new CoGroupedRDD[K](
      Seq(first.asInstanceOf[RDD[(K, _)]], second.asInstanceOf[RDD[(K, _)]]),
      part
    ).asInstanceOf[RDD[(K, Array[Iterable[V]])]]
  }

}
