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

package org.apache.spark.streaming.kafka

import org.apache.spark.Partition

/** @param topic kafka topic name kafka主题名称
  * @param partition kafka partition id kafka分区id
  * @param fromOffset inclusive starting offset 包括起始偏移量
  * @param untilOffset exclusive ending offset 独占结束偏移
  * @param host preferred kafka host, i.e. the leader at the time the rdd was created
  *              首选的kafka主机,即在创建rdd时的领导者
  * @param port preferred kafka host's port 首选kafka主机的端口
  */
private[kafka]
class KafkaRDDPartition(
  val index: Int,
  val topic: String,
  val partition: Int,
  val fromOffset: Long,
  val untilOffset: Long,
  val host: String,
  val port: Int
) extends Partition {
  /** Number of messages this partition refers to
    * 此分区引用的消息数*/
  def count(): Long = untilOffset - fromOffset
}
