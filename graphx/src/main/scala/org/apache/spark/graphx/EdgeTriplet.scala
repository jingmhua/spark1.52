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

package org.apache.spark.graphx

/**
 * An edge triplet represents an edge along with the vertex attributes of its neighboring vertices.
 * 边三元组表示边缘以及其相邻顶点的顶点属性
 * @tparam VD the type of the vertex attribute.
 * @tparam ED the type of the edge attribute
 */
class EdgeTriplet[VD, ED] extends Edge[ED] {
  /**
   * The source vertex attribute
    * 源顶点属性
   */
  var srcAttr: VD = _ // nullValue[VD]

  /**
   * The destination vertex attribute
    * 目标顶点属性
   */
  var dstAttr: VD = _ // nullValue[VD]

  /**
   * Set the edge properties of this triplet.
    * 设置此三元组的边缘属性
   */
  protected[spark] def set(other: Edge[ED]): EdgeTriplet[VD, ED] = {
    srcId = other.srcId
    dstId = other.dstId
    attr = other.attr
    this
  }

  /**
   * Given one vertex in the edge return the other vertex.
    * 给定边缘中的一个顶点返回另一个顶点
   *
   * @param vid the id one of the two vertices on the edge
   * @return the attribute for the other vertex on the edge
   */
  def otherVertexAttr(vid: VertexId): VD =
    if (srcId == vid) dstAttr else { assert(dstId == vid); srcAttr }

  /**
   * Get the vertex object for the given vertex in the edge.
    * 获取边缘中给定顶点的顶点对象
   *
   * @param vid the id of one of the two vertices on the edge
   * @return the attr for the vertex with that id
   */
  def vertexAttr(vid: VertexId): VD =
    if (srcId == vid) srcAttr else { assert(dstId == vid); dstAttr }

  override def toString: String = ((srcId, srcAttr), (dstId, dstAttr), attr).toString()

  def toTuple: ((VertexId, VD), (VertexId, VD), ED) = ((srcId, srcAttr), (dstId, dstAttr), attr)
}
