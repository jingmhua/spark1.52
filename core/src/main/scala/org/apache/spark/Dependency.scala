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

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.ShuffleHandle

/**
 * :: DeveloperApi ::
 * Base class for dependencies.
 * Dependency代表了RDD之间的依赖关系,即血缘
 */
@DeveloperApi
abstract class Dependency[T] extends Serializable {
  def rdd: RDD[T]
}


/**
 * :: DeveloperApi ::
 * Base class for dependencies where each partition of the child RDD depends on a small number
 * of partitions of the parent RDD. Narrow dependencies allow for pipelined execution.
 * 依赖在每个分区的子RDD取决于少数的父RDD分区基类,窄的依赖关系允许流水线执行
 */
@DeveloperApi
abstract class NarrowDependency[T](_rdd: RDD[T]) extends Dependency[T] {
  /**
   * Get the parent partitions for a child partition.
    * 获取子分区所依赖的父RDD的分区
   * @param partitionId a partition of the child RDD 子RDD的分区
   * @return the partitions of the parent RDD that the child partition depends upon
    *         子分区所依赖的父RDD的分区
   */
  def getParents(partitionId: Int): Seq[Int]//返回子rdd的partitionId依赖的所有父Rdd的partitions

  override def rdd: RDD[T] = _rdd
}


/**
 * :: DeveloperApi ::
 * Represents a dependency on the output of a shuffle stage. Note that in the case of shuffle,
 * the RDD is transient since we don't need it on the executor side.
  * 表示对洗牌阶段的输出的依赖,请注意,在洗牌的情况下,RDD是暂时性的,因为我们不需要它在执行者端。
 * 宽依赖,子RDD依赖于parent RDD的所有partition,Spark根据宽依赖将DAG划分不同的Stage
 * @param _rdd the parent RDD 父RDD
 * @param partitioner partitioner used to partition the shuffle output 分区器用于分区随机输出
 * @param serializer [[org.apache.spark.serializer.Serializer Serializer]] to use. If set to None,
 *                   the default serializer, as specified by `spark.serializer` config option, will
 *                   be used.
  *                   如果设置为None,将使用由`spark.serializer`配置选项指定的默认序列化程序
 * @param keyOrdering key ordering for RDD's shuffles 关键排序RDD的洗牌
 * @param aggregator map/reduce-side aggregator for RDD's shuffle, map / reduce-side聚合器用于RDD的洗牌
 * @param mapSideCombine whether to perform partial aggregation (also known as map-side combine)
  *                       是否执行部分聚合(也称为映射侧组合)
 */
@DeveloperApi
class ShuffleDependency[K, V, C](
    //scala <:中上界类型限定,是指他必须是Product2[K,_]的子类型
    @transient _rdd: RDD[_ <: Product2[K, V]],
    val partitioner: Partitioner,
    val serializer: Option[Serializer] = None,
    val keyOrdering: Option[Ordering[K]] = None,
    val aggregator: Option[Aggregator[K, V, C]] = None,
    val mapSideCombine: Boolean = false)//是否需要在worker端进行combine操作
  extends Dependency[Product2[K, V]] {

  override def rdd: RDD[Product2[K, V]] = _rdd.asInstanceOf[RDD[Product2[K, V]]]
//获取新的shuffleId
  val shuffleId: Int = _rdd.context.newShuffleId()
//向shuffleManager注册shuffle的信息
  val shuffleHandle: ShuffleHandle = _rdd.context.env.shuffleManager.registerShuffle(
    shuffleId, _rdd.partitions.size, this)

  _rdd.sparkContext.cleaner.foreach(_.registerShuffleForCleanup(this))
}


/**
 * :: DeveloperApi ::
 * Represents a one-to-one dependency between partitions of the parent and child RDDs.
  * 表示父和子RDD的分区之间的一对一依赖关系。
 */
@DeveloperApi
class OneToOneDependency[T](rdd: RDD[T]) extends NarrowDependency[T](rdd) {
  //Rdd仅仅依赖于parent RDD相同ID的partition,
  // List(partitionId)构造List列表只有一个元素
  override def getParents(partitionId: Int): List[Int] = List(partitionId)
}


/**
 * :: DeveloperApi ::
 * 范围的依赖,它仅仅被UnionRDD使用把多个RDD合成一个RDD,这些RDD是被拼接而成,即每个parent RDD的partitions
 * 相对顺序不会变,只不过每个parent RDD在UnionRDD中的partitions的起始位置不同.
 * Represents a one-to-one dependency between ranges of partitions in the parent and child RDDs.
  * 表示父子节点中的分区范围之间的一对一依赖关系
 * @param rdd the parent RDD
 * @param inStart the start of the range in the parent RDD,inStart是parent RDD中partitions起始位置
 * @param outStart the start of the range in the child RDD,outStart是在UnionRDD中的起始位置
 * @param length the length of the range,length就是parent RDD中partitions的数量
 */
@DeveloperApi
class RangeDependency[T](rdd: RDD[T], inStart: Int, outStart: Int, length: Int)
  extends NarrowDependency[T](rdd) {
  // List(partitionId)构造List列表只有一个元素
  override def getParents(partitionId: Int): List[Int] = {
    if (partitionId >= outStart && partitionId < outStart + length) {
      List(partitionId - outStart + inStart)
    } else {
      Nil
    }
  }
}
