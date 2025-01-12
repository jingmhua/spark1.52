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

package org.apache.spark.deploy.master

import java.util.Date

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.deploy.ApplicationDescription
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.Utils

private[spark] class ApplicationInfo(
  val startTime: Long,
  val id: String,
  val desc: ApplicationDescription,
  val submitDate: Date,
  val driver: RpcEndpointRef,
  defaultCores: Int)
    extends Serializable {
  //枚举类型赋值
  @transient var state: ApplicationState.Value = _
  @transient var executors: mutable.HashMap[Int, ExecutorDesc] = _
  @transient var removedExecutors: ArrayBuffer[ExecutorDesc] = _
  @transient var coresGranted: Int = _
  @transient var endTime: Long = _
  @transient var appSource: ApplicationSource = _

  // A cap on the number of executors this application can have at any given time.
  //执行者的数量这个应用程序可以在任何给定的时间
  // By default, this is infinite. Only after the first allocation request is issued by the
  // application will this be set to a finite value. This is used for dynamic allocation.
  //默认情况下,这是无限的,只有在应用程序发出第一个分配请求之后,这将被设置为有限的值,这用于动态分配
  @transient private[master] var executorLimit: Int = _

  @transient private var nextExecutorId: Int = _

  init() //初始化方法

  private def readObject(in: java.io.ObjectInputStream): Unit = Utils.tryOrIOException {
    in.defaultReadObject()
    init()
  }
  /**
   * 创建ApplicationSource,声明了executors用于缓存分配Application的executor
   */
  private def init() {
    //枚举类型赋值
    state = ApplicationState.WAITING //等待
    executors = new mutable.HashMap[Int, ExecutorDesc]
    coresGranted = 0 //
    endTime = -1L //结束时间
    appSource = new ApplicationSource(this)
    nextExecutorId = 0
    removedExecutors = new ArrayBuffer[ExecutorDesc]
    executorLimit = Integer.MAX_VALUE
  }

  private def newExecutorId(useID: Option[Int] = None): Int = {
    useID match {
      case Some(id) =>
        nextExecutorId = math.max(nextExecutorId, id + 1)
        id
      case None =>
        val id = nextExecutorId
        nextExecutorId += 1
        id
    }
  }
  /**
   * 物理分配的步骤如下:
   * 1)首先使用WorkerInfo,逻辑分配的CPU核数及内存大小创建ExecutorDesc
   * 2)将ExecutorDesc添加Application的executors缓存中
   * 3)增加已授权得到的内核数
   */
  private[master] def addExecutor(
    worker: WorkerInfo,
    cores: Int,
    useID: Option[Int] = None): ExecutorDesc = {
    val exec = new ExecutorDesc(newExecutorId(useID), this, worker, cores, desc.memoryPerExecutorMB)
    executors(exec.id) = exec
    coresGranted += cores
    exec
  }
  //删除Executor
  private[master] def removeExecutor(exec: ExecutorDesc) {
    if (executors.contains(exec.id)) {
      //ArrayBuffer操作
      removedExecutors += executors(exec.id)
      //HashMap操作
      executors -= exec.id
      //Int 操作
      coresGranted -= exec.cores
    }
  }
  //当前app申请的maxcpus
  private val requestedCores = desc.maxCores.getOrElse(defaultCores)
  //coresLeft表示的是该app还有cpu资源没申请到,coresGranted==允许的cpus
  private[master] def coresLeft: Int = requestedCores - coresGranted

  private var _retryCount = 0 //重试次数

  private[master] def retryCount = _retryCount
  //添加重试数
  private[master] def incrementRetryCount() = {
    _retryCount += 1
    _retryCount
  }

  private[master] def resetRetryCount() = _retryCount = 0
  //标识应用程序已经完成
  private[master] def markFinished(endState: ApplicationState.Value) {
    state = endState
    endTime = System.currentTimeMillis()
  }

  private[master] def isFinished: Boolean = {
    state != ApplicationState.WAITING && state != ApplicationState.RUNNING
  }

  /**
   * Return the limit on the number of executors this application can have.
   * For testing only.
   * 返回限制应用程序executors数
   */
  private[deploy] def getExecutorLimit: Int = executorLimit

  def duration: Long = {
    if (endTime != -1) {
      endTime - startTime
    } else {
      System.currentTimeMillis() - startTime
    }
  }

}
