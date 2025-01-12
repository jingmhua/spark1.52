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

package org.apache.spark.graphx.impl

import scala.reflect.{classTag, ClassTag}

import org.apache.spark.HashPartitioner
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.{RDD, ShuffledRDD}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.GraphImpl._
import org.apache.spark.graphx.util.BytecodeUtils


/**
 * An implementation of [[org.apache.spark.graphx.Graph]] to support computation on graphs.
  *
  * [[org.apache.spark.graphx.Graph]]的实现,以支持图上的计算
 *
 * Graphs are represented using two RDDs: `vertices`, which contains vertex attributes and the
 * routing information for shipping vertex attributes to edge partitions, and
 * `replicatedVertexView`, which contains edges and the vertex attributes mentioned by each edge.
  *
  * 图形使用两个RDD表示：`vertices`,其中包含顶点属性和用于将顶点属性发送到边缘分区的路由信息,
  * 以及`replicatedVertexView`,其中包含边和每个边提到的顶点属性
 */
class GraphImpl[VD: ClassTag, ED: ClassTag] protected (
    @transient val vertices: VertexRDD[VD],
    @transient val replicatedVertexView: ReplicatedVertexView[VD, ED])
  extends Graph[VD, ED] with Serializable {

  /** Default constructor is provided to support serialization
    * 提供默认构造函数以支持序列化*/
  protected def this() = this(null, null)

  @transient override val edges: EdgeRDDImpl[ED, VD] = replicatedVertexView.edges

  /** Return a RDD that brings edges together with their source and destination vertices.
    * 返回一个RDD,它将边缘与其源和目标顶点结合在一起*/
  @transient override lazy val triplets: RDD[EdgeTriplet[VD, ED]] = {
    replicatedVertexView.upgrade(vertices, true, true)
    replicatedVertexView.edges.partitionsRDD.mapPartitions(_.flatMap {
      case (pid, part) => part.tripletIterator()
    })
  }

  override def persist(newLevel: StorageLevel): Graph[VD, ED] = {
    vertices.persist(newLevel)
    replicatedVertexView.edges.persist(newLevel)
    this
  }

  override def cache(): Graph[VD, ED] = {
    vertices.cache()
    replicatedVertexView.edges.cache()
    this
  }

  override def checkpoint(): Unit = {
    vertices.checkpoint()
    replicatedVertexView.edges.checkpoint()
  }

  override def isCheckpointed: Boolean = {
    vertices.isCheckpointed && replicatedVertexView.edges.isCheckpointed
  }

  override def getCheckpointFiles: Seq[String] = {
    Seq(vertices.getCheckpointFile, replicatedVertexView.edges.getCheckpointFile).flatMap {
      case Some(path) => Seq(path)
      case None => Seq()
    }
  }

  override def unpersist(blocking: Boolean = true): Graph[VD, ED] = {
    unpersistVertices(blocking)
    replicatedVertexView.edges.unpersist(blocking)
    this
  }

  override def unpersistVertices(blocking: Boolean = true): Graph[VD, ED] = {
    vertices.unpersist(blocking)
    // TODO: unpersist the replicated vertices in `replicatedVertexView` but leave the edges alone
    this
  }

  override def partitionBy(partitionStrategy: PartitionStrategy): Graph[VD, ED] = {
    partitionBy(partitionStrategy, edges.partitions.size)
  }

  override def partitionBy(
      partitionStrategy: PartitionStrategy, numPartitions: Int): Graph[VD, ED] = {
    val edTag = classTag[ED]
    val vdTag = classTag[VD]
    val newEdges = edges.withPartitionsRDD(edges.map { e =>
      val part: PartitionID = partitionStrategy.getPartition(e.srcId, e.dstId, numPartitions)
      (part, (e.srcId, e.dstId, e.attr))
    }
      .partitionBy(new HashPartitioner(numPartitions))
      .mapPartitionsWithIndex( { (pid, iter) =>
        val builder = new EdgePartitionBuilder[ED, VD]()(edTag, vdTag)
        iter.foreach { message =>
          val data = message._2
          builder.add(data._1, data._2, data._3)
        }
        val edgePartition = builder.toEdgePartition
        Iterator((pid, edgePartition))
      }, preservesPartitioning = true)).cache()
    GraphImpl.fromExistingRDDs(vertices.withEdges(newEdges), newEdges)
  }

  override def reverse: Graph[VD, ED] = {
    new GraphImpl(vertices.reverseRoutingTables(), replicatedVertexView.reverse())
  }

  override def mapVertices[VD2: ClassTag]
    (f: (VertexId, VD) => VD2)(implicit eq: VD =:= VD2 = null): Graph[VD2, ED] = {
    // The implicit parameter eq will be populated by the compiler if VD and VD2 are equal, and left
    // null if not
    //如果VD和VD2相等,则隐式参数eq将由编译器填充,否则保留为null
    if (eq != null) {
      vertices.cache()
      // The map preserves type, so we can use incremental replication
      val newVerts = vertices.mapVertexPartitions(_.map(f)).cache()
      val changedVerts = vertices.asInstanceOf[VertexRDD[VD2]].diff(newVerts)
      val newReplicatedVertexView = replicatedVertexView.asInstanceOf[ReplicatedVertexView[VD2, ED]]
        .updateVertices(changedVerts)
      new GraphImpl(newVerts, newReplicatedVertexView)
    } else {
      // The map does not preserve type, so we must re-replicate all vertices
      //Map不保留类型，因此我们必须重新复制所有顶点
      GraphImpl(vertices.mapVertexPartitions(_.map(f)), replicatedVertexView.edges)
    }
  }

  override def mapEdges[ED2: ClassTag](
      f: (PartitionID, Iterator[Edge[ED]]) => Iterator[ED2]): Graph[VD, ED2] = {
    val newEdges = replicatedVertexView.edges
      .mapEdgePartitions((pid, part) => part.map(f(pid, part.iterator)))
    new GraphImpl(vertices, replicatedVertexView.withEdges(newEdges))
  }

  override def mapTriplets[ED2: ClassTag](
      f: (PartitionID, Iterator[EdgeTriplet[VD, ED]]) => Iterator[ED2],
      tripletFields: TripletFields): Graph[VD, ED2] = {
    vertices.cache()
    replicatedVertexView.upgrade(vertices, tripletFields.useSrc, tripletFields.useDst)
    val newEdges = replicatedVertexView.edges.mapEdgePartitions { (pid, part) =>
      part.map(f(pid, part.tripletIterator(tripletFields.useSrc, tripletFields.useDst)))
    }
    new GraphImpl(vertices, replicatedVertexView.withEdges(newEdges))
  }

  override def subgraph(
      epred: EdgeTriplet[VD, ED] => Boolean = x => true,
      vpred: (VertexId, VD) => Boolean = (a, b) => true): Graph[VD, ED] = {
    vertices.cache()
    // Filter the vertices, reusing the partitioner and the index from this graph
    //过滤顶点,重用分区器和此图中的索引
    val newVerts = vertices.mapVertexPartitions(_.filter(vpred))
    // Filter the triplets. We must always upgrade the triplet view fully because vpred always runs
    // on both src and dst vertices
    //,我们必须始终完全升级三元组视图,因为vpred始终在src和dst顶点上运行
    replicatedVertexView.upgrade(vertices, true, true)
    val newEdges = replicatedVertexView.edges.filter(epred, vpred)
    new GraphImpl(newVerts, replicatedVertexView.withEdges(newEdges))
  }

  override def mask[VD2: ClassTag, ED2: ClassTag] (
      other: Graph[VD2, ED2]): Graph[VD, ED] = {
    val newVerts = vertices.innerJoin(other.vertices) { (vid, v, w) => v }
    val newEdges = replicatedVertexView.edges.innerJoin(other.edges) { (src, dst, v, w) => v }
    new GraphImpl(newVerts, replicatedVertexView.withEdges(newEdges))
  }

  override def groupEdges(merge: (ED, ED) => ED): Graph[VD, ED] = {
    val newEdges = replicatedVertexView.edges.mapEdgePartitions(
      (pid, part) => part.groupEdges(merge))
    new GraphImpl(vertices, replicatedVertexView.withEdges(newEdges))
  }

  // ///////////////////////////////////////////////////////////////////////////////////////////////
  // Lower level transformation methods
  //较低级别的转换方法
  // ///////////////////////////////////////////////////////////////////////////////////////////////

  override def mapReduceTriplets[A: ClassTag](
      mapFunc: EdgeTriplet[VD, ED] => Iterator[(VertexId, A)],
      reduceFunc: (A, A) => A,
      activeSetOpt: Option[(VertexRDD[_], EdgeDirection)]): VertexRDD[A] = {

    def sendMsg(ctx: EdgeContext[VD, ED, A]) {
      mapFunc(ctx.toEdgeTriplet).foreach { kv =>
        val id = kv._1
        val msg = kv._2
        if (id == ctx.srcId) {
          ctx.sendToSrc(msg)
        } else {
          assert(id == ctx.dstId)
          ctx.sendToDst(msg)
        }
      }
    }

    val mapUsesSrcAttr = accessesVertexAttr(mapFunc, "srcAttr")
    val mapUsesDstAttr = accessesVertexAttr(mapFunc, "dstAttr")
    val tripletFields = new TripletFields(mapUsesSrcAttr, mapUsesDstAttr, true)

    aggregateMessagesWithActiveSet(sendMsg, reduceFunc, tripletFields, activeSetOpt)
  }

  override def aggregateMessagesWithActiveSet[A: ClassTag](
      sendMsg: EdgeContext[VD, ED, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeSetOpt: Option[(VertexRDD[_], EdgeDirection)]): VertexRDD[A] = {

    vertices.cache()
    // For each vertex, replicate its attribute only to partitions where it is
    // in the relevant position in an edge.
    //对于每个顶点,仅将其属性复制到位于边缘相关位置的分区
    replicatedVertexView.upgrade(vertices, tripletFields.useSrc, tripletFields.useDst)
    val view = activeSetOpt match {
      case Some((activeSet, _)) =>
        replicatedVertexView.withActiveSet(activeSet)
      case None =>
        replicatedVertexView
    }
    val activeDirectionOpt = activeSetOpt.map(_._2)

    // Map and combine.
    val preAgg = view.edges.partitionsRDD.mapPartitions(_.flatMap {
      case (pid, edgePartition) =>
        // Choose scan method
        val activeFraction = edgePartition.numActives.getOrElse(0) / edgePartition.indexSize.toFloat
        activeDirectionOpt match {
          case Some(EdgeDirection.Both) =>
            if (activeFraction < 0.8) {
              edgePartition.aggregateMessagesIndexScan(sendMsg, mergeMsg, tripletFields,
                EdgeActiveness.Both)
            } else {
              edgePartition.aggregateMessagesEdgeScan(sendMsg, mergeMsg, tripletFields,
                EdgeActiveness.Both)
            }
          case Some(EdgeDirection.Either) =>
            // TODO: Because we only have a clustered index on the source vertex ID, we can't filter
            // the index here. Instead we have to scan all edges and then do the filter.
            edgePartition.aggregateMessagesEdgeScan(sendMsg, mergeMsg, tripletFields,
              EdgeActiveness.Either)
          case Some(EdgeDirection.Out) =>
            if (activeFraction < 0.8) {
              edgePartition.aggregateMessagesIndexScan(sendMsg, mergeMsg, tripletFields,
                EdgeActiveness.SrcOnly)
            } else {
              edgePartition.aggregateMessagesEdgeScan(sendMsg, mergeMsg, tripletFields,
                EdgeActiveness.SrcOnly)
            }
          case Some(EdgeDirection.In) =>
            edgePartition.aggregateMessagesEdgeScan(sendMsg, mergeMsg, tripletFields,
              EdgeActiveness.DstOnly)
          case _ => // None
            edgePartition.aggregateMessagesEdgeScan(sendMsg, mergeMsg, tripletFields,
              EdgeActiveness.Neither)
        }
    }).setName("GraphImpl.aggregateMessages - preAgg")

    // do the final reduction reusing the index map
    //最后减少重用索引图
    vertices.aggregateUsingIndex(preAgg, mergeMsg)
  }

  override def outerJoinVertices[U: ClassTag, VD2: ClassTag]
      (other: RDD[(VertexId, U)])
      (updateF: (VertexId, VD, Option[U]) => VD2)
      (implicit eq: VD =:= VD2 = null): Graph[VD2, ED] = {
    // The implicit parameter eq will be populated by the compiler if VD and VD2 are equal, and left
    // null if not
    if (eq != null) {
      vertices.cache()
      // updateF preserves type, so we can use incremental replication
      //updateF保留类型，因此我们可以使用增量复制
      val newVerts = vertices.leftJoin(other)(updateF).cache()
      val changedVerts = vertices.asInstanceOf[VertexRDD[VD2]].diff(newVerts)
      val newReplicatedVertexView = replicatedVertexView.asInstanceOf[ReplicatedVertexView[VD2, ED]]
        .updateVertices(changedVerts)
      new GraphImpl(newVerts, newReplicatedVertexView)
    } else {
      // updateF does not preserve type, so we must re-replicate all vertices
      val newVerts = vertices.leftJoin(other)(updateF)
      GraphImpl(newVerts, replicatedVertexView.edges)
    }
  }

  /** Test whether the closure accesses the the attribute with name `attrName`.
    * 测试闭包是否访问名为`attrName`的属性*/
  private def accessesVertexAttr(closure: AnyRef, attrName: String): Boolean = {
    try {
      BytecodeUtils.invokedMethod(closure, classOf[EdgeTriplet[VD, ED]], attrName)
    } catch {
      case _: ClassNotFoundException => true // if we don't know, be conservative
    }
  }
} // end of class GraphImpl


object GraphImpl {

  /** Create a graph from edges, setting referenced vertices to `defaultVertexAttr`.
    * 从边创建图形,将引用的顶点设置为`defaultVertexAttr`*/
  def apply[VD: ClassTag, ED: ClassTag](
      edges: RDD[Edge[ED]],
      defaultVertexAttr: VD,
      edgeStorageLevel: StorageLevel,
      vertexStorageLevel: StorageLevel): GraphImpl[VD, ED] = {
    //根据边构建图
    fromEdgeRDD(EdgeRDD.fromEdges(edges), defaultVertexAttr, edgeStorageLevel, vertexStorageLevel)
  }

  /** Create a graph from EdgePartitions, setting referenced vertices to `defaultVertexAttr`.
    * 从EdgePartitions创建图形,将引用的顶点设置为“defaultVertexAttr”*/
  def fromEdgePartitions[VD: ClassTag, ED: ClassTag](
      edgePartitions: RDD[(PartitionID, EdgePartition[ED, VD])],
      defaultVertexAttr: VD,
      edgeStorageLevel: StorageLevel,
      vertexStorageLevel: StorageLevel): GraphImpl[VD, ED] = {
    fromEdgeRDD(EdgeRDD.fromEdgePartitions(edgePartitions), defaultVertexAttr, edgeStorageLevel,
      vertexStorageLevel)
  }

  /** Create a graph from vertices and edges, setting missing vertices to `defaultVertexAttr`.
    * 从顶点和边创建图，将缺少的顶点设置为`defaultVertexAttr`*/
  def apply[VD: ClassTag, ED: ClassTag](
      vertices: RDD[(VertexId, VD)],
      edges: RDD[Edge[ED]],
      defaultVertexAttr: VD,
      edgeStorageLevel: StorageLevel,
      vertexStorageLevel: StorageLevel): GraphImpl[VD, ED] = {
    //根据边构建图
    val edgeRDD = EdgeRDD.fromEdges(edges)(classTag[ED], classTag[VD])
      .withTargetStorageLevel(edgeStorageLevel)
    val vertexRDD = VertexRDD(vertices, edgeRDD, defaultVertexAttr)
      .withTargetStorageLevel(vertexStorageLevel)
    GraphImpl(vertexRDD, edgeRDD)
  }

  /**
   * Create a graph from a VertexRDD and an EdgeRDD with arbitrary replicated vertices. The
   * VertexRDD must already be set up for efficient joins with the EdgeRDD by calling
   * `VertexRDD.withEdges` or an appropriate VertexRDD constructor.
    *
    * 从具有任意复制顶点的VertexRDD和EdgeRDD创建图形,必须通过调用“VertexRDD.withEdges”或
    * 适当的VertexRDD构造函数,设置VertexRDD以实现与EdgeRDD的有效连接。
   */
  def apply[VD: ClassTag, ED: ClassTag](
      vertices: VertexRDD[VD],
      edges: EdgeRDD[ED]): GraphImpl[VD, ED] = {

    vertices.cache()

    // Convert the vertex partitions in edges to the correct type
    //将边的顶点分区转换为正确的类型
    val newEdges = edges.asInstanceOf[EdgeRDDImpl[ED, _]]
      .mapEdgePartitions((pid, part) => part.withoutVertexAttributes[VD])
      .cache()

    GraphImpl.fromExistingRDDs(vertices, newEdges)
  }

  /**
   * Create a graph from a VertexRDD and an EdgeRDD with the same replicated vertex type as the
   * vertices. The VertexRDD must already be set up for efficient joins with the EdgeRDD by calling
   * `VertexRDD.withEdges` or an appropriate VertexRDD constructor.
    * 从VertexRDD和EdgeRDD创建具有与顶点相同的复制顶点类型的图形,必须通过调用“VertexRDD.withEdges”或
    * 适当的VertexRDD构造函数，设置VertexRDD以实现与EdgeRDD的有效连接
   */
  def fromExistingRDDs[VD: ClassTag, ED: ClassTag](
      vertices: VertexRDD[VD],
      edges: EdgeRDD[ED]): GraphImpl[VD, ED] = {
    new GraphImpl(vertices, new ReplicatedVertexView(edges.asInstanceOf[EdgeRDDImpl[ED, VD]]))
  }

  /**
   * Create a graph from an EdgeRDD with the correct vertex type, setting missing vertices to
   * `defaultVertexAttr`. The vertices will have the same number of partitions as the EdgeRDD.
    *
    * 使用正确的顶点类型从EdgeRDD创建图形,将缺少的顶点设置为“defaultVertexAttr”,顶点将具有与EdgeRDD相同数量的分区
   */
  private def fromEdgeRDD[VD: ClassTag, ED: ClassTag](
      edges: EdgeRDDImpl[ED, VD],
      defaultVertexAttr: VD,
      edgeStorageLevel: StorageLevel,
      vertexStorageLevel: StorageLevel): GraphImpl[VD, ED] = {
    val edgesCached = edges.withTargetStorageLevel(edgeStorageLevel).cache()
    val vertices = VertexRDD.fromEdges(edgesCached, edgesCached.partitions.size, defaultVertexAttr)
      .withTargetStorageLevel(vertexStorageLevel)
    fromExistingRDDs(vertices, edgesCached)
  }

} // end of object GraphImpl
