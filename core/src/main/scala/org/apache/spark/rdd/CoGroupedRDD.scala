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

import scala.language.existentials

import java.io.{IOException, ObjectOutputStream}

import scala.collection.mutable.ArrayBuffer

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.collection.{ExternalAppendOnlyMap, AppendOnlyMap, CompactBuffer}
import org.apache.spark.util.Utils
import org.apache.spark.serializer.Serializer

/** The references to rdd and splitIndex are transient because redundant information is stored
  * in the CoGroupedRDD object.  Because CoGroupedRDD is serialized separately from
  * CoGroupPartition, if rdd and splitIndex aren't transient, they'll be included twice in the
  * task closure.
  * 对rdd和splitIndex的引用是暂时的，因为冗余信息存储在CoGroupedRDD对象中,因为CoGroupedRDD与CoGroupPartition分开序列化,
  * 如果rdd和splitIndex不是暂时的，那么它们将在任务闭包中包含两次*/
private[spark] case class NarrowCoGroupSplitDep(
    @transient rdd: RDD[_],
    @transient splitIndex: Int,
    var split: Partition
  ) extends Serializable {

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream): Unit = Utils.tryOrIOException {
    // Update the reference to parent split at the time of task serialization
    //在任务序列化时更新对父拆分的引用
    split = rdd.partitions(splitIndex)
    oos.defaultWriteObject()
  }
}

/**
 * Stores information about the narrow dependencies used by a CoGroupedRdd.
  * 存储有关CoGroupedRdd使用的窄依赖关系的信息
 *
 * @param narrowDeps maps to the dependencies variable in the parent RDD: for each one to one
 *                   dependency in dependencies, narrowDeps has a NarrowCoGroupSplitDep (describing
 *                   the partition for that dependency) at the corresponding index. The size of
 *                   narrowDeps should always be equal to the number of parents.
 */
private[spark] class CoGroupPartition(
    idx: Int, val narrowDeps: Array[Option[NarrowCoGroupSplitDep]])
  extends Partition with Serializable {
  override val index: Int = idx
  override def hashCode(): Int = idx
}

/**
 * :: DeveloperApi ::
 * A RDD that cogroups its parents. For each key k in parent RDDs, the resulting RDD contains a
 * tuple with the list of values for that key.
  * 一个共同组合其父母的RDD,对于父RDD中的每个密钥k,生成的RDD包含一个元组，该元组包含该key的值列表
 *
 * Note: This is an internal API. We recommend users use RDD.cogroup(...) instead of
 * instantiating this directly.
   注意：这是一个内部API, 我们建议用户使用RDD.cogroup（...）,而不是直接实例化
 * @param rdds parent RDDs.
 * @param part partitioner used to partition the shuffle output
 */
@DeveloperApi
class CoGroupedRDD[K](@transient var rdds: Seq[RDD[_ <: Product2[K, _]]], part: Partitioner)
  extends RDD[(K, Array[Iterable[_]])](rdds.head.context, Nil) {

  // For example, `(k, a) cogroup (k, b)` produces k -> Array(ArrayBuffer as, ArrayBuffer bs).
  // Each ArrayBuffer is represented as a CoGroup, and the resulting Array as a CoGroupCombiner.
  // CoGroupValue is the intermediate state of each value before being merged in compute.
  private type CoGroup = CompactBuffer[Any]
  private type CoGroupValue = (Any, Int)  // Int is dependency number Int是依赖数
  private type CoGroupCombiner = Array[CoGroup]

  private var serializer: Option[Serializer] = None

  /** Set a serializer for this RDD's shuffle, or null to use the default (spark.serializer)
    * 为此RDD的shuffle设置一个串行化器,或者使用默认值（spark.serializer） */
  def setSerializer(serializer: Serializer): CoGroupedRDD[K] = {
    this.serializer = Option(serializer)
    this
  }

  override def getDependencies: Seq[Dependency[_]] = {
    rdds.map { rdd: RDD[_] =>
      if (rdd.partitioner == Some(part)) {
        logDebug("Adding one-to-one dependency with " + rdd)
        new OneToOneDependency(rdd)
      } else {
        logDebug("Adding shuffle dependency with " + rdd)
        new ShuffleDependency[K, Any, CoGroupCombiner](
          rdd.asInstanceOf[RDD[_ <: Product2[K, _]]], part, serializer)
      }
    }
  }

  override def getPartitions: Array[Partition] = {
    val array = new Array[Partition](part.numPartitions)
    for (i <- 0 until array.length) {
      // Each CoGroupPartition will have a dependency per contributing RDD
      //每个CoGroupPartition将有一个依赖性贡献RDD
      array(i) = new CoGroupPartition(i, rdds.zipWithIndex.map { case (rdd, j) =>
        // Assume each RDD contributed a single dependency, and get it
        //假设每个RDD贡献了一个依赖关系,并得到它
        dependencies(j) match {
          case s: ShuffleDependency[_, _, _] =>
            None
          case _ =>
            Some(new NarrowCoGroupSplitDep(rdd, i, rdd.partitions(i)))
        }
      }.toArray)
    }
    array
  }

  override val partitioner: Some[Partitioner] = Some(part)

  override def compute(s: Partition, context: TaskContext): Iterator[(K, Array[Iterable[_]])] = {
    val sparkConf = SparkEnv.get.conf
    //spark.shuffle.spill用于指定Shuffle过程中如果内存中的数据超过阈值(参考spark.shuffle.memoryFraction的设置),
    //那么是否需要将部分数据临时写入外部存储。如果设置为false，那么这个过程就会一直使用内,最后再合并到最终的Shuffle输出文件中去。
    val externalSorting = sparkConf.getBoolean("spark.shuffle.spill", true)
    val split = s.asInstanceOf[CoGroupPartition]
    val numRdds = dependencies.length

    // A list of (rdd iterator, dependency number) pairs
    //（rdd迭代器，依赖关系号）对的列表
    val rddIterators = new ArrayBuffer[(Iterator[Product2[K, Any]], Int)]
    for ((dep, depNum) <- dependencies.zipWithIndex) dep match {
      case oneToOneDependency: OneToOneDependency[Product2[K, Any]] @unchecked =>
        val dependencyPartition = split.narrowDeps(depNum).get.split
        // Read them from the parent
        val it = oneToOneDependency.rdd.iterator(dependencyPartition, context)
        rddIterators += ((it, depNum))

      case shuffleDependency: ShuffleDependency[_, _, _] =>
        // Read map outputs of shuffle 读取shuffle的map输出
        val it = SparkEnv.get.shuffleManager
          .getReader(shuffleDependency.shuffleHandle, split.index, split.index + 1, context)
          .read()
        rddIterators += ((it, depNum))
    }

    if (!externalSorting) {
      val map = new AppendOnlyMap[K, CoGroupCombiner]
      val update: (Boolean, CoGroupCombiner) => CoGroupCombiner = (hadVal, oldVal) => {
        if (hadVal) oldVal else Array.fill(numRdds)(new CoGroup)
      }
      val getCombiner: K => CoGroupCombiner = key => {
        map.changeValue(key, update)
      }
      rddIterators.foreach { case (it, depNum) =>
        while (it.hasNext) {
          val kv = it.next()
          getCombiner(kv._1)(depNum) += kv._2
        }
      }
      new InterruptibleIterator(context,
        map.iterator.asInstanceOf[Iterator[(K, Array[Iterable[_]])]])
    } else {
      val map = createExternalMap(numRdds)
      for ((it, depNum) <- rddIterators) {
        map.insertAll(it.map(pair => (pair._1, new CoGroupValue(pair._2, depNum))))
      }
      context.taskMetrics().incMemoryBytesSpilled(map.memoryBytesSpilled)
      context.taskMetrics().incDiskBytesSpilled(map.diskBytesSpilled)
      context.internalMetricsToAccumulators(
        InternalAccumulator.PEAK_EXECUTION_MEMORY).add(map.peakMemoryUsedBytes)
      new InterruptibleIterator(context,
        map.iterator.asInstanceOf[Iterator[(K, Array[Iterable[_]])]])
    }
  }

  private def createExternalMap(numRdds: Int)
    : ExternalAppendOnlyMap[K, CoGroupValue, CoGroupCombiner] = {

    val createCombiner: (CoGroupValue => CoGroupCombiner) = value => {
      val newCombiner = Array.fill(numRdds)(new CoGroup)
      newCombiner(value._2) += value._1
      newCombiner
    }
    val mergeValue: (CoGroupCombiner, CoGroupValue) => CoGroupCombiner =
      (combiner, value) => {
      combiner(value._2) += value._1
      combiner
    }
    val mergeCombiners: (CoGroupCombiner, CoGroupCombiner) => CoGroupCombiner =
      (combiner1, combiner2) => {
        var depNum = 0
        while (depNum < numRdds) {
          combiner1(depNum) ++= combiner2(depNum)
          depNum += 1
        }
        combiner1
      }
    new ExternalAppendOnlyMap[K, CoGroupValue, CoGroupCombiner](
      createCombiner, mergeValue, mergeCombiners)
  }

  override def clearDependencies() {
    super.clearDependencies()
    rdds = null
  }
}
