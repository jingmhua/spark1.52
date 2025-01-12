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
package org.apache.spark.streaming.rdd

import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.rdd.BlockRDD
import org.apache.spark.storage.{BlockId, StorageLevel}
import org.apache.spark.streaming.util._
import org.apache.spark.util.SerializableConfiguration

/**
 * Partition class for [[org.apache.spark.streaming.rdd.WriteAheadLogBackedBlockRDD]].
 * It contains information about the id of the blocks having this partition's data and
 * the corresponding record handle in the write ahead log that backs the partition.
 * 它包含有这个分区的数据块的标识的信息,以及在备份分区的写前日志中的相应记录句柄
 * @param index index of the partition 索引分区
 * @param blockId id of the block having the partition data blockid ID的块具有分区数据
 * @param isBlockIdValid Whether the block Ids are valid (i.e., the blocks are present in the Spark
 *                         executors). If not, then block lookups by the block ids will be skipped.
 *                         By default, this is an empty array signifying true for all the blocks.
 * @param walRecordHandle Handle of the record in a write ahead log having the partition data
 * WAL(Write-Ahead-Log):在处理数据插入和删除的过程中用来记录操作内容的一种日志
 * WriteAheadLog BackedBlock 预写式日志支持块
 */
private[streaming]
class WriteAheadLogBackedBlockRDDPartition(
    val index: Int,
    val blockId: BlockId,
    val isBlockIdValid: Boolean,
    val walRecordHandle: WriteAheadLogRecordHandle
  ) extends Partition


/**
 * This class represents a special case of the BlockRDD where the data blocks in
 * the block manager are also backed by data in write ahead logs. For reading
 * 这类代表一个特殊的情况下,在块管理器的数据块,通过数据blockrdd也支持提前写日志
 * the data, this RDD first looks up the blocks by their ids in the block manager.
 * If it does not find them, it looks up the WAL using the corresponding record handle.
 * The lookup of the blocks from the block manager can be skipped by setting the corresponding
 * element in isBlockIdValid to false. This is a performance optimization which does not affect
 * correctness, and it can be used in situations where it is known that the block
 * does not exist in the Spark executors (e.g. after a failed driver is restarted).
 *
 *
 * @param sc SparkContext
 * @param blockIds Ids of the blocks that contains this RDD's data 
 * 								 IDS的块包含该RDD数据
 * @param walRecordHandles Record handles in write ahead logs that contain this RDD's data
 * 												预写日志记录处理包含该RDD数据
 * @param isBlockIdValid Whether the block Ids are valid (i.e., the blocks are present in the Spark
 *                         executors). If not, then block lookups by the block ids will be skipped.
 *                         块标识是否有效(块代表Spark在executors存在),如果不是,那块查找的块ID将被忽略
 *                         By default, this is an empty array signifying true for all the blocks.
 *                         默认情况下,这意味着真正的所有块空数组,
 * @param storeInBlockManager Whether to store a block in the block manager
 *                            after reading it from the WAL
 *                            是否将块存储在块管理器中,预写日志从块管理器读取
 * @param storageLevel storage level to store when storing in block manager
 * 										 存储在块管理器中存储的存储级别
 *                     (applicable when storeInBlockManager = true)
 */
private[streaming]
class WriteAheadLogBackedBlockRDD[T: ClassTag](
    @transient sc: SparkContext,
    @transient blockIds: Array[BlockId],
    @transient val walRecordHandles: Array[WriteAheadLogRecordHandle],
    @transient isBlockIdValid: Array[Boolean] = Array.empty,
    storeInBlockManager: Boolean = false,
    storageLevel: StorageLevel = StorageLevel.MEMORY_ONLY_SER)
  extends BlockRDD[T](sc, blockIds) {

  require(
    blockIds.length == walRecordHandles.length,
    s"Number of block Ids (${blockIds.length}) must be " +
      s" same as number of WAL record handles (${walRecordHandles.length})")

  require(
    isBlockIdValid.isEmpty || isBlockIdValid.length == blockIds.length,
    s"Number of elements in isBlockIdValid (${isBlockIdValid.length}) must be " +
      s" same as number of block Ids (${blockIds.length})")

  // Hadoop configuration is not serializable, so broadcast it as a serializable.
  //Hadoop的配置是不可序列化的,所以广播作为一个序列化。
  @transient private val hadoopConfig = sc.hadoopConfiguration
  private val broadcastedHadoopConf = new SerializableConfiguration(hadoopConfig)

  override def isValid(): Boolean = true

  override def getPartitions: Array[Partition] = {
    assertValid()
    Array.tabulate(blockIds.length) { i =>
      val isValid = if (isBlockIdValid.length == 0) true else isBlockIdValid(i)
      new WriteAheadLogBackedBlockRDDPartition(i, blockIds(i), isValid, walRecordHandles(i))
    }
  }

  /**
   * Gets the partition data by getting the corresponding block from the block manager.
   * 通过从块管理器获取相应的块获取分区数据,如果块不存在,则将数据从写前的日志文件中的相应记录中读取
   * If the block does not exist, then the data is read from the corresponding record
   * in write ahead log files.
   */
  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    assertValid()
    val hadoopConf = broadcastedHadoopConf.value
    val blockManager = SparkEnv.get.blockManager
    val partition = split.asInstanceOf[WriteAheadLogBackedBlockRDDPartition]
    val blockId = partition.blockId

    def getBlockFromBlockManager(): Option[Iterator[T]] = {
      blockManager.get(blockId).map(_.data.asInstanceOf[Iterator[T]])
    }

    def getBlockFromWriteAheadLog(): Iterator[T] = {
      var dataRead: ByteBuffer = null
      var writeAheadLog: WriteAheadLog = null
      try {
        // The WriteAheadLogUtils.createLog*** method needs a directory to create a
        // WriteAheadLog object as the default FileBasedWriteAheadLog needs a directory for
        // writing log data. However, the directory is not needed if data needs to be read, hence
        // a dummy path is provided to satisfy the method parameter requirements.
        // FileBasedWriteAheadLog will not create any file or directory at that path.
        // FileBasedWriteAheadLog will not create any file or directory at that path. Also,
        // this dummy directory should not already exist otherwise the WAL will try to recover
        // past events from the directory and throw errors.
        val nonExistentDirectory = new File(
          System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString).getAbsolutePath
        writeAheadLog = WriteAheadLogUtils.createLogForReceiver(
          SparkEnv.get.conf, nonExistentDirectory, hadoopConf)
        dataRead = writeAheadLog.read(partition.walRecordHandle)
      } catch {
        case NonFatal(e) =>
          throw new SparkException(
            s"Could not read data from write ahead log record ${partition.walRecordHandle}", e)
      } finally {
        if (writeAheadLog != null) {
          writeAheadLog.close()
          writeAheadLog = null
        }
      }
      if (dataRead == null) {
        throw new SparkException(
          s"Could not read data from write ahead log record ${partition.walRecordHandle}, " +
            s"read returned null")
      }
      logInfo(s"Read partition data of $this from write ahead log, record handle " +
        partition.walRecordHandle)
      if (storeInBlockManager) {
        blockManager.putBytes(blockId, dataRead, storageLevel)
        logDebug(s"Stored partition data of $this into block manager with level $storageLevel")
        dataRead.rewind()
      }
      blockManager.dataDeserialize(blockId, dataRead).asInstanceOf[Iterator[T]]
    }

    if (partition.isBlockIdValid) {
      getBlockFromBlockManager().getOrElse { getBlockFromWriteAheadLog() }
    } else {
      getBlockFromWriteAheadLog()
    }
  }

  /**
   * Get the preferred location of the partition. This returns the locations of the block
   * if it is present in the block manager, else if FileBasedWriteAheadLogSegment is used,
   * it returns the location of the corresponding file segment in HDFS .
   * 获取分区的首选位置,如果在块管理器中存在,则返回该块的位置,如果filebasedwriteaheadlogsegment使用
   * 它返回在HDFS的相应文件段的位置
   */
  override def getPreferredLocations(split: Partition): Seq[String] = {
    val partition = split.asInstanceOf[WriteAheadLogBackedBlockRDDPartition]
    val blockLocations = if (partition.isBlockIdValid) {
      getBlockIdLocations().get(partition.blockId)
    } else {
      None
    }

    blockLocations.getOrElse {
      partition.walRecordHandle match {
        case fileSegment: FileBasedWriteAheadLogSegment =>
          try {
            HdfsUtils.getFileSegmentLocations(
              fileSegment.path, fileSegment.offset, fileSegment.length, hadoopConfig)
          } catch {
            case NonFatal(e) =>
              logError("Error getting WAL file segment locations", e)
              Seq.empty
          }
        case _ =>
          Seq.empty
      }
    }
  }
}
