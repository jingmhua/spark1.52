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

import scala.collection.mutable.ArrayBuffer
import scala.reflect.{classTag, ClassTag}

import org.apache.spark.{Logging, Partition, SparkContext, SparkException, TaskContext}
import org.apache.spark.partial.{PartialResult, BoundedDouble}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.NextIterator

import kafka.api.{FetchRequestBuilder, FetchResponse}
import kafka.common.{ErrorMapping, TopicAndPartition}
import kafka.consumer.SimpleConsumer
import kafka.message.{MessageAndMetadata, MessageAndOffset}
import kafka.serializer.Decoder
import kafka.utils.VerifiableProperties

/**
 * A batch-oriented interface for consuming from Kafka.
  * 一个面向批次的接口,用于从Kafka消费
 * Starting and ending offsets are specified in advance,
 * so that you can control exactly-once semantics.
  * 提前指定开始和结束偏移,以便您可以精确地控制一次语义
 * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
 * configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers" to be set
 * with Kafka broker(s) specified in host1:port1,host2:port2 form.
 * @param offsetRanges offset ranges that define the Kafka data belonging to this RDD
  *                     偏移距离定义属于此RDD的卡夫卡数据
 * @param messageHandler function for translating each message into the desired type
  *                       将每个消息翻译成所需类型的功能
 */
private[kafka]
class KafkaRDD[
  K: ClassTag,
  V: ClassTag,
  U <: Decoder[_]: ClassTag,
  T <: Decoder[_]: ClassTag,
  R: ClassTag] private[spark] (
    sc: SparkContext,
    kafkaParams: Map[String, String],
    val offsetRanges: Array[OffsetRange],
    leaders: Map[TopicAndPartition, (String, Int)],
    messageHandler: MessageAndMetadata[K, V] => R
  ) extends RDD[R](sc, Nil) with Logging with HasOffsetRanges {
  override def getPartitions: Array[Partition] = {
    offsetRanges.zipWithIndex.map { case (o, i) =>
        val (host, port) = leaders(TopicAndPartition(o.topic, o.partition))
        new KafkaRDDPartition(i, o.topic, o.partition, o.fromOffset, o.untilOffset, host, port)
    }.toArray
  }

  override def count(): Long = offsetRanges.map(_.count).sum

  override def countApprox(
      timeout: Long,
      confidence: Double = 0.95
  ): PartialResult[BoundedDouble] = {
    val c = count
    new PartialResult(new BoundedDouble(c, 1.0, c, c), true)
  }

  override def isEmpty(): Boolean = count == 0L

  override def take(num: Int): Array[R] = {
    val nonEmptyPartitions = this.partitions
      .map(_.asInstanceOf[KafkaRDDPartition])
      .filter(_.count > 0)

    if (num < 1 || nonEmptyPartitions.size < 1) {
      return new Array[R](0)
    }

    // Determine in advance how many messages need to be taken from each partition
    //提前确定每个分区需要采取多少个消息
    val parts = nonEmptyPartitions.foldLeft(Map[Int, Int]()) { (result, part) =>
      val remain = num - result.values.sum
      if (remain > 0) {
        val taken = Math.min(remain, part.count)
        result + (part.index -> taken.toInt)
      } else {
        result
      }
    }

    val buf = new ArrayBuffer[R]
    val res = context.runJob(
      this,
      (tc: TaskContext, it: Iterator[R]) => it.take(parts(tc.partitionId)).toArray,
      parts.keys.toArray)
    res.foreach(buf ++= _)
    buf.toArray
  }

  override def getPreferredLocations(thePart: Partition): Seq[String] = {
    val part = thePart.asInstanceOf[KafkaRDDPartition]
    // TODO is additional hostname resolution necessary here
    Seq(part.host)
  }

  private def errBeginAfterEnd(part: KafkaRDDPartition): String =
    s"Beginning offset ${part.fromOffset} is after the ending offset ${part.untilOffset} " +
      s"for topic ${part.topic} partition ${part.partition}. " +
      "You either provided an invalid fromOffset, or the Kafka topic has been damaged"

  private def errRanOutBeforeEnd(part: KafkaRDDPartition): String =
    s"Ran out of messages before reaching ending offset ${part.untilOffset} " +
    s"for topic ${part.topic} partition ${part.partition} start ${part.fromOffset}." +
    " This should not happen, and indicates that messages may have been lost"

  private def errOvershotEnd(itemOffset: Long, part: KafkaRDDPartition): String =
    s"Got ${itemOffset} > ending offset ${part.untilOffset} " +
    s"for topic ${part.topic} partition ${part.partition} start ${part.fromOffset}." +
    " This should not happen, and indicates a message may have been skipped"

  override def compute(thePart: Partition, context: TaskContext): Iterator[R] = {
    val part = thePart.asInstanceOf[KafkaRDDPartition]
    assert(part.fromOffset <= part.untilOffset, errBeginAfterEnd(part))
    if (part.fromOffset == part.untilOffset) {
      log.info(s"Beginning offset ${part.fromOffset} is the same as ending offset " +
        s"skipping ${part.topic} ${part.partition}")
      Iterator.empty
    } else {
      new KafkaRDDIterator(part, context)
    }
  }

  private class KafkaRDDIterator(
      part: KafkaRDDPartition,
      context: TaskContext) extends NextIterator[R] {

    context.addTaskCompletionListener{ context => closeIfNeeded() }

    log.info(s"Computing topic ${part.topic}, partition ${part.partition} " +
      s"offsets ${part.fromOffset} -> ${part.untilOffset}")

    val kc = new KafkaCluster(kafkaParams)
    val keyDecoder = classTag[U].runtimeClass.getConstructor(classOf[VerifiableProperties])
      .newInstance(kc.config.props)
      .asInstanceOf[Decoder[K]]
    val valueDecoder = classTag[T].runtimeClass.getConstructor(classOf[VerifiableProperties])
      .newInstance(kc.config.props)
      .asInstanceOf[Decoder[V]]
    val consumer = connectLeader
    var requestOffset = part.fromOffset
    var iter: Iterator[MessageAndOffset] = null

    // The idea is to use the provided preferred host, except on task retry atttempts,
    // to minimize number of kafka metadata requests
    //这个想法是使用提供的首选主机,除了任务重试尝试,以最小化卡夫卡元数据请求的数量
    private def connectLeader: SimpleConsumer = {
      if (context.attemptNumber > 0) {
        kc.connectLeader(part.topic, part.partition).fold(
          errs => throw new SparkException(
            s"Couldn't connect to leader for topic ${part.topic} ${part.partition}: " +
              errs.mkString("\n")),
          consumer => consumer
        )
      } else {
        kc.connect(part.host, part.port)
      }
    }

    private def handleFetchErr(resp: FetchResponse) {
      if (resp.hasError) {
        val err = resp.errorCode(part.topic, part.partition)
        if (err == ErrorMapping.LeaderNotAvailableCode ||
          err == ErrorMapping.NotLeaderForPartitionCode) {
          log.error(s"Lost leader for topic ${part.topic} partition ${part.partition}, " +
            s" sleeping for ${kc.config.refreshLeaderBackoffMs}ms")
          Thread.sleep(kc.config.refreshLeaderBackoffMs)
        }
        // Let normal rdd retry sort out reconnect attempts
        //让正常的rdd重试排序重新连接尝试
        throw ErrorMapping.exceptionFor(err)
      }
    }

    private def fetchBatch: Iterator[MessageAndOffset] = {
      val req = new FetchRequestBuilder()
        .addFetch(part.topic, part.partition, requestOffset, kc.config.fetchMessageMaxBytes)
        .build()
      val resp = consumer.fetch(req)
      handleFetchErr(resp)
      // kafka may return a batch that starts before the requested offset
      //kafka可能会返回在请求的偏移量之前启动的批次
      resp.messageSet(part.topic, part.partition)
        .iterator
        .dropWhile(_.offset < requestOffset)
    }

    override def close(): Unit = {
      if (consumer != null) {
        consumer.close()
      }
    }

    override def getNext(): R = {
      if (iter == null || !iter.hasNext) {
        iter = fetchBatch
      }
      if (!iter.hasNext) {
        assert(requestOffset == part.untilOffset, errRanOutBeforeEnd(part))
        finished = true
        null.asInstanceOf[R]
      } else {
        val item = iter.next()
        if (item.offset >= part.untilOffset) {
          assert(item.offset == part.untilOffset, errOvershotEnd(item.offset, part))
          finished = true
          null.asInstanceOf[R]
        } else {
          requestOffset = item.nextOffset
          messageHandler(new MessageAndMetadata(
            part.topic, part.partition, item.message, item.offset, keyDecoder, valueDecoder))
        }
      }
    }
  }
}

private[kafka]
object KafkaRDD {
  import KafkaCluster.LeaderOffset

  /**
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   * configuration parameters</a>.
   *   Requires "metadata.broker.list" or "bootstrap.servers" to be set with Kafka broker(s),
   *   NOT zookeeper servers, specified in host1:port1,host2:port2 form.
    *  需要使用Kafka代理设置“metadata.broker.list”或“bootstrap.servers”
    *   在主机1中指定的不是zookeeper服务器：port1，host2：port2表单。
   * @param fromOffsets per-topic/partition Kafka offsets defining the (inclusive)
   *  starting point of the batch 每个主题/分区Kafka偏移定义批处理的（包含）起始点
   * @param untilOffsets per-topic/partition Kafka offsets defining the (exclusive)
   *  ending point of the batch 每个主题/分区Kafka偏移定义批次的（排他）终点
   * @param messageHandler function for translating each message into the desired type
    *                       将每个消息翻译成所需类型的功能
   */
  def apply[
    K: ClassTag,
    V: ClassTag,
    U <: Decoder[_]: ClassTag,
    T <: Decoder[_]: ClassTag,
    R: ClassTag](
      sc: SparkContext,
      kafkaParams: Map[String, String],
      fromOffsets: Map[TopicAndPartition, Long],
      untilOffsets: Map[TopicAndPartition, LeaderOffset],
      messageHandler: MessageAndMetadata[K, V] => R
    ): KafkaRDD[K, V, U, T, R] = {
    val leaders = untilOffsets.map { case (tp, lo) =>
        tp -> (lo.host, lo.port)
    }.toMap

    val offsetRanges = fromOffsets.map { case (tp, fo) =>
        val uo = untilOffsets(tp)
        OffsetRange(tp.topic, tp.partition, fo, uo.offset)
    }.toArray

    new KafkaRDD[K, V, U, T, R](sc, kafkaParams, offsetRanges, leaders, messageHandler)
  }
}
