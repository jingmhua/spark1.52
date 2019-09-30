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

package org.apache.spark.scheduler

import java.nio.ByteBuffer
import java.util.{ TimerTask, Timer }
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.language.postfixOps
import scala.util.Random

import org.apache.spark._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.scheduler.TaskLocality.TaskLocality
import org.apache.spark.util.{ ThreadUtils, Utils }
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.storage.BlockManagerId

/**
 * Schedules tasks for multiple types of clusters by acting through a SchedulerBackend.
  *
  * 通过执行SchedulerBackend来调度多种类型的集群的任务
  *
 * It can also work with a local setup by using a LocalBackend and setting isLocal to true.
  * 它也可以通过使用LocalBackend并将isLocal设置为true来设置本地设置
 * It handles common logic, like determining a scheduling order across jobs, waking up to launch
 * speculative tasks, etc.
  *
 *它处理常见的逻辑,例如确定作业之间的调度顺序,唤醒启动投机任务等
  *
 * Clients should first call initialize() and start(), then submit task sets through the
 * runTasks method.
  *
  * 客户端应首先调用initialize()和start(),然后通过runTasks方法提交任务集
 *
 * THREADING: SchedulerBackends and task-submitting clients can call this class from multiple
 * threads, so it needs locks in public API methods to maintain its state. In addition, some
 * SchedulerBackends synchronize on themselves when they want to send events here, and then
 * acquire a lock on us, so we need to make sure that we don't try to lock the backend while
 * we are holding a lock on ourselves.
  *
  * THREADING：SchedulerBackends和任务提交客户端可以从多个线程调用此类,因此它需要使用公共API方法的锁来维护其状态,
  * 另外,一些SchedulerBackends当他们想在这里发送事件时自己进行同步,然后在我们上获取一个锁,所以我们需要确保在我们自己锁定时不要锁定后端。
  *
 * TaskSchedulerImpl 构造过程如下:
 * 1)从SparkConf中读取配置信息,包括每个任务分配的CPU数,调度模式(调度模式有FAIR和FIFO两种,默认FIFO,可以修改Spark.scheduler.mode参数改变),
 * 2)创建TaskResult,它的作用是通过线程池(Executors.newFixedThreadPool创建,默认4个线程,线程名字以task-result-getter开头,线程工厂默认是
 *    Executors.defualt.ThreadFactory)对Worker上的Executor发送Task的执行结果进行处理.
 */
private[spark] class TaskSchedulerImpl(
  val sc: SparkContext,
  val maxTaskFailures: Int,
  isLocal: Boolean = false)
    extends TaskScheduler with Logging {
  //spark.task.maxFailures任务Task的最大重试次数
  def this(sc: SparkContext) = this(sc, sc.conf.getInt("spark.task.maxFailures", 4))

  val conf = sc.conf

  // How often to check for speculative tasks
  //Spark多长时间进行检查task运行状态用以推测,以毫秒为单位
  val SPECULATION_INTERVAL_MS = conf.getTimeAsMs("spark.speculation.interval", "100ms")

  private val speculationScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("task-scheduler-speculation")

  // Threshold above which we warn user initial TaskSet may be starved(饥饿)
  val STARVATION_TIMEOUT_MS = conf.getTimeAsMs("spark.starvation.timeout", "15s")

  // CPUs to request per task
  //spark.task.cpus 每个任务分配的CPU内核数,默认1
  val CPUS_PER_TASK = conf.getInt("spark.task.cpus", 1)

  // TaskSetManagers are not thread safe, so any access to one should be synchronized
  // on this class.
  //TaskSetManagers不是线程安全的,所以对这个类的任何访问都应该同步,
  //taskSetsByStageIdAndAttempt存储的是stageId->[taskSet.stageAttemptId->TaskSetManager]  
  private val taskSetsByStageIdAndAttempt = new HashMap[Int, HashMap[Int, TaskSetManager]]

  private[scheduler] val taskIdToTaskSetManager = new HashMap[Long, TaskSetManager]
  val taskIdToExecutorId = new HashMap[Long, String]
  //是否接受任务  
  @volatile private var hasReceivedTask = false
  //标志位launchedTask初始化为false,用它来标记是否有task被成功分配或者launched
  @volatile private var hasLaunchedTask = false
  private val starvationTimer = new Timer(true)

  // Incrementing task IDs 递增任务ID
  val nextTaskId = new AtomicLong(0)

  // Which executor IDs we have executors on
  //保存集群当前所有可用的 executor id
  val activeExecutorIds = new HashSet[String]

  // The set of executors we have on each host; this is used to compute hostsAlive, which
  // in turn is used to decide when we can attain data locality on a given host
  //我们在每个主机上的一组执行器;这用于计算hostsAlive,这又用来决定何时可以在给定主机上获得数据本地化
  //执行设定每个主机还活着,key为 host,value 为该 host 上的 active executors
  protected val executorsByHost = new HashMap[String, HashSet[String]]
  //hostsByRack保存key为rack,value为该 rack上所有作为 taskSetManager优先位置的 hosts
  protected val hostsByRack = new HashMap[String, HashSet[String]]

  protected val executorIdToHost = new HashMap[String, String]

  // Listener object to pass upcalls into
  //侦听器对象传递upcalls
  var dagScheduler: DAGScheduler = null

  var backend: SchedulerBackend = null
  //
  val mapOutputTracker = SparkEnv.get.mapOutputTracker

  var schedulableBuilder: SchedulableBuilder = null

  var rootPool: Pool = null
  // default scheduler is FIFO
  //任务的最终调度模式,实际都是落实到接SchedulerBackend的具体实现上.,默认先进先出
  private val schedulingModeConf = conf.get("spark.scheduler.mode", "FIFO")
  //调度模式,默认先进先出
  val schedulingMode: SchedulingMode = try {
    SchedulingMode.withName(schedulingModeConf.toUpperCase)
  } catch {
    case e: java.util.NoSuchElementException =>
      throw new SparkException(s"Unrecognized spark.scheduler.mode: $schedulingModeConf")
  }

  // This is a var so that we can reset it for testing purposes.
  //taskResultGetter作用是通过线程池,对worker上的executor发送的Task的执行结果进行处理.
  //创建TaskResult,它的作用是通过线程池(Executors.newFixedThreadPool创建,默认4个线程,
  //线程名字以task-result-getter开头,线程工厂默认是Executors.defualt.ThreadFactory)
  //对Worker上的Executor发送Task的执行结果进行处理.
  private[spark] var taskResultGetter = new TaskResultGetter(sc.env, this)
  //
  override def setDAGScheduler(dagScheduler: DAGScheduler) {
    this.dagScheduler = dagScheduler
  }
  /**
   * *
   * 初始化过程如下:
   * 1)使TaskSchedulerImpl持有LocalBackend的引用
   * 2)创建Pool,Pool中缓存了调度队列,调度算法及TaskSetManager集合等信息
   * 3)创建FIFOSchedulableBuilder 用来操作Pool中的调度队列
   */

  def initialize(backend: SchedulerBackend) {

    this.backend = backend
    // temporarily set rootPool name to empty
    //临时将rootPool名称设置为空
    rootPool = new Pool("", schedulingMode, 0, 0)
    schedulableBuilder = {
      schedulingMode match {
        case SchedulingMode.FIFO =>
          new FIFOSchedulableBuilder(rootPool)
        case SchedulingMode.FAIR =>
          new FairSchedulableBuilder(rootPool, conf)
      }
    }
    schedulableBuilder.buildPools()
  }

  def newTaskId(): Long = nextTaskId.getAndIncrement()

  override def start() {
    //向actorSystem注册了LocalActor
    backend.start()
    //spark.speculation为true 对于非本地模式,那么对于指定时间未返回的task将会启动另外的task来执行
    //其实对于一般的应用,这个的确可能会减少任务的执行时间,但是也浪费了集群的计算资源。因此对于离线应用来说,这个设置是不推荐的
    if (!isLocal && conf.getBoolean("spark.speculation", false)) {
      logInfo("Starting speculative execution thread")
      speculationScheduler.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = Utils.tryOrStopSparkContext(sc) {
          checkSpeculatableTasks()
        }
      }, SPECULATION_INTERVAL_MS, SPECULATION_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
  }
  //目的是为了等待backend就绪
  override def postStartHook() {
    waitBackendReady()
  }

  /**
   * DAGScheduler的submitMissingTasks方法调用本方法，提交taskset
   * @param taskSet
   */
  override def submitTasks(taskSet: TaskSet) {
    //获取TaskSet中的tasks  
    val tasks = taskSet.tasks
    logInfo("Adding task set " + taskSet.id + " with " + tasks.length + " tasks")
    this.synchronized { // 使用synchronized进行同步  
      //为taskSet创建一个TaskSetManager任务集管理器,最大提交任务失败次数
      //TaskSetManager跟踪每个task的执行状况,设置任务集调度策略
      val manager = createTaskSetManager(taskSet, maxTaskFailures)
      //获取taskSet对应的stageId  
      val stage = taskSet.stageId
      // taskSetsByStageIdAndAttempt存储的是stageId->[taskSet.stageAttemptId->TaskSetManager]  
      // 更新taskSetsByStageIdAndAttempt,将上述对应关系存入  
      val stageTaskSets =
        taskSetsByStageIdAndAttempt.getOrElseUpdate(stage, new HashMap[Int, TaskSetManager])
      stageTaskSets(taskSet.stageAttemptId) = manager
      //查看是否存在冲突的taskSet,如果存在,抛出IllegalStateException异常  
      val conflictingTaskSet = stageTaskSets.exists {
        case (_, ts) =>
          ts.taskSet != taskSet && !ts.isZombie
      }
      if (conflictingTaskSet) {
        throw new IllegalStateException(s"more than one active taskSet for stage $stage:" +
          s" ${stageTaskSets.toSeq.map { _._2.taskSet.id }.mkString(",")}")
      }
      //设置任务集调度策略,现支持两种调度策略,FiFo先进先出和FAIR公平调度
      schedulableBuilder.addTaskSetManager(manager, manager.taskSet.properties)
      //如果不是本地任务,且不再接受任务  
      if (!isLocal && !hasReceivedTask) {
        //超时取消
        starvationTimer.scheduleAtFixedRate(new TimerTask() {
          override def run() {
            if (!hasLaunchedTask) {
              logWarning("Initial job has not accepted any resources; " +
                "check your cluster UI to ensure that workers are registered " +
                "and have sufficient resources")
            } else {
              this.cancel()
            }
          }
        }, STARVATION_TIMEOUT_MS, STARVATION_TIMEOUT_MS)
      }
      // 设置标志位hasReceivedTask为true,接受任务  
      hasReceivedTask = true
    }
    //向localActor发送reviveOffers消息
    backend.reviveOffers()
  }

  // Label as private[scheduler] to allow tests to swap in different task set managers if necessary
  //标签为私有[scheduler],允许测试在不同的任务集管理器中进行交换,如果需要的话
  //创建任务集管理器
  private[scheduler] def createTaskSetManager(
    taskSet: TaskSet,
    maxTaskFailures: Int): TaskSetManager = {
    //maxTaskFailures任务集最大失败数
    new TaskSetManager(this, taskSet, maxTaskFailures)
  }

  override def cancelTasks(stageId: Int, interruptThread: Boolean): Unit = synchronized {
    logInfo("Cancelling stage " + stageId)
    taskSetsByStageIdAndAttempt.get(stageId).foreach { attempts =>
      attempts.foreach {
        case (_, tsm) =>
          // There are two possible cases here:
          //这里有两种可能的情况：
          // 1. The task set manager has been created and some tasks have been scheduled.
          //    In this case, send a kill signal to the executors to kill the task and then abort
          //    the stage.
          //    已经创建了任务集管理器,并且已经安排了一些任务,在这种情况下,向执行者发送杀戮信号来杀死任务,然后中止stage。
          // 2. The task set manager has been created but no tasks has been scheduled. In this case,
          //    simply abort the stage.
          //    任务集管理器已创建,但没有安排任务,在这种情况下,只需中止stage。
          tsm.runningTasksSet.foreach { tid =>
            val execId = taskIdToExecutorId(tid)
            backend.killTask(tid, execId, interruptThread)
          }
          tsm.abort("Stage %s cancelled".format(stageId))
          logInfo("Stage %d was cancelled".format(stageId))
      }
    }
  }

  /**
   * Called to indicate that all task attempts (including speculated tasks) associated with the
   * given TaskSetManager have completed, so state associated with the TaskSetManager should be
   * cleaned up.
    *
    * 被称为指示与所有任务尝试（包括推测的任务）相关联给TaskSetManager已经完成，所以与TaskSetManager关联的状态应该被清理。
   * tasksetmanager已经完成间接调用所有任务的尝试（包括猜测任务）,清理相关TaskSetManager状态
   */
  def taskSetFinished(manager: TaskSetManager): Unit = synchronized {
    taskSetsByStageIdAndAttempt.get(manager.taskSet.stageId).foreach { taskSetsForStage =>
      taskSetsForStage -= manager.taskSet.stageAttemptId
      if (taskSetsForStage.isEmpty) {
        taskSetsByStageIdAndAttempt -= manager.taskSet.stageId
      }
    }
    //
    manager.parent.removeSchedulable(manager)
    logInfo("Removed TaskSet %s, whose tasks have all completed, from pool %s"
      .format(manager.taskSet.id, manager.parent.name))
  }
  //分配资源
  private def resourceOfferSingleTaskSet(
    taskSet: TaskSetManager,
    maxLocality: TaskLocality,
    shuffledOffers: Seq[WorkerOffer],
    availableCpus: Array[Int],
    tasks: Seq[ArrayBuffer[TaskDescription]]): Boolean = {
    //标志位launchedTask初始化为false,用它来标记是否有task被成功分配或者launched
    var launchedTask = false
    //循环shuffledOffers,即每个可用executor  
    for (i <- 0 until shuffledOffers.size) { //顺序遍历当前存在的Executor
      //根据WorkerOffer的executorId和host找到需要执行的任务并进一步资源处理  
      val execId = shuffledOffers(i).executorId
      val host = shuffledOffers(i).host
      //如果executor上可利用cpu数目大于每个task需要的数目,则继续task分配  
      //CPUS_PER_TASK为参数spark.task.cpus配置的值,未配置的话默认为1
      if (availableCpus(i) >= CPUS_PER_TASK) { //每台机器可用的计算资源
        try {
          //调用每个TaskSetManager的resourceOffer方法,根据execId,host找到需要执行的任务最佳位置
          //处理返回的每个TaskDescription,
          for (task <- taskSet.resourceOffer(execId, host, maxLocality)) {
            // 分配task成功  
            // 将task加入到tasks对应位置  
            // 注意,tasks为一个空的,根据shuffledOffers和其可用cores生成的有一定结构的列表  
            tasks(i) += task
            val tid = task.taskId
            taskIdToTaskSetManager(tid) = taskSet // taskId与TaskSetManager的映射关系  
            taskIdToExecutorId(tid) = execId // taskId与ExecutorId的映射关系  
            executorsByHost(host) += execId //host上对应的executor的映射关系  
            //availableCpus数目减去每个任务分配的CPU核数
            availableCpus(i) -= CPUS_PER_TASK
            //确保availableCpus(i)不小于0
            assert(availableCpus(i) >= 0)
            // 标志位launchedTask设置为true 
            launchedTask = true
          }
        } catch {
          case e: TaskNotSerializableException =>
            logError(s"Resource offer failed, task set ${taskSet.name} was not serializable")
            // Do not offer resources for this task, but don't throw an error to allow other
            // task sets to be submitted.
            return launchedTask
        }
      }
    }
    return launchedTask
  }

  /**
   * Called by cluster manager to offer resources on slaves. We respond by asking our active task
   * sets for tasks in order of priority. We fill each node with tasks in a round-robin manner so
   * that tasks are balanced across the cluster.
    * 由群集管理员调用slaves方面提供资源,我们通过以优先级的顺序询问我们的活动任务集来应对任务,
    * 我们以循环方式填充每个节点的任务,使得任务在集群中平衡,
   */

  /**
   * *
   * resourceOffers方法会将已经提交的tasks进行一次优先级排序,这个排序算法目前是两种：FIFO或FAIR。
   * 响应CoarseGrainedSchedulerBackend的资源调度请求,为每个Task具体分配资源
   */
  def resourceOffers(offers: Seq[WorkerOffer]): Seq[Seq[TaskDescription]] = synchronized {
    // Mark each slave as alive and remember its hostname
    // Also track if new executor is added
    //newExecAvail为false,这个标志位是在新的slave被添加时被设置的一个标志true
    var newExecAvail = false
    //循环offers,WorkerOffer为包含executorId、host、cores的结构体,代表集群中的可用executor资源  
    for (o <- offers) {
      // 利用HashMap存储executorId->host映射的集合  
      executorIdToHost(o.executorId) = o.host
      //保存集群当前所有可用的 executor id->HashSet
      activeExecutorIds += o.executorId
      //如果有新Executor加入,executors的集合 
      // 这个executorsByHost被用来计算host活跃性,反过来我们用它来决定在给定的主机上何时实现数据本地性  
      if (!executorsByHost.contains(o.host)) { //如果executorsByHost中不存在对应的host
        //executorsByHost中添加一条记录,key为host,value为new HashSet[String]()  
        executorsByHost(o.host) = new HashSet[String]()
        //并向DagSchedulerEventProcessActor发送ExecutorAdd事件
        //调用DAGScheduler的executorAdded()方法处理  
        executorAdded(o.executorId, o.host)
        //新的slave加入时,标志位newExecAvail设置为true  
        newExecAvail = true
      }
      //更新hostsByRack 
      for (rack <- getRackForHost(o.host)) {
        hostsByRack.getOrElseUpdate(rack, new HashSet[String]()) += o.host
      }
    }
    // Randomly shuffle offers to avoid always placing tasks on the same set of workers.
    // 随机洗牌shuffle offers以避免总将任务总是放在同一组workers上执行  
    val shuffledOffers = Random.shuffle(offers)
    // Build a list of tasks to assign to each worker.
    //根据每个WorkerOffer的可用的CPU核数创建同等尺寸的任务描述[TaskDescription]数组
    val tasks = shuffledOffers.map(o => new ArrayBuffer[TaskDescription](o.cores))
    //将每个WorkerOffer的可用的CPU核数统计到可用的CPU[availableCpus]数组中
    val availableCpus = shuffledOffers.map(o => o.cores).toArray
    //对rootPool中的所有TaskManager按照调度算法排序
    val sortedTaskSets = rootPool.getSortedTaskSetQueue
    //循环每个taskSet,
    for (taskSet <- sortedTaskSets) {
      //记录日志 
      logDebug("parentName: %s, name: %s, runningTasks: %s".format(
        taskSet.parent.name, taskSet.name, taskSet.runningTasks))
      //如果存在新的活跃的executor(新的slave节点被添加时)  
      if (newExecAvail) {
        //重新计算该TaskSetManager的就近原则
        taskSet.executorAdded()
      }
    }

    // Take each TaskSet in our scheduling order, and then offer it each node in increasing order
    // of locality levels so that it gets a chance to launch local tasks on all of them.
    //以我们的调度顺序中的每个TaskSet,然后按照地点级别的增加顺序提供每个节点,以便它有机会在其上启动本地任务。
    // NOTE: the preferredLocality order: PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY
    //注意：preferredLocality顺序：PROCESS_LOCAL，NODE_LOCAL，NO_PREF，RACK_LOCAL，ANY
    //标志位launchedTask初始化为false,用它来标记是否有task被成功分配或者launched
    var launchedTask = false
    //为从rootPool里获取TaskSetManager列表分配资源,按照位置本地性规则调度每个TaskSet,分配的原则是就近原则,  
    //位置本地性规则的顺序是：PROCESS_LOCAL（同进程）、NODE_LOCAL（同节点）、NO_PREF、RACK_LOCAL（同机架）、ANY（任何）  
    for (taskSet <- sortedTaskSets; maxLocality <- taskSet.myLocalityLevels) {
      do {
        //调用resourceOfferSingleTaskSet()方法任务集分配资源 
        launchedTask = resourceOfferSingleTaskSet(
          taskSet, maxLocality, shuffledOffers, availableCpus, tasks)
      } while (launchedTask)
    }
    //设置标志位hasLaunchedTask  
    if (tasks.size > 0) {
      hasLaunchedTask = true
    }
    return tasks
  }
  /**
   * Task在Executor执行完成时,会通过向Driver发送StatusUpdate的消息来通知Driver任务的状态更新为TaskState.FINISHED
   * Driver首先会将任务的状态更新通知TaskScheduler,然后在这个Executor上重新分配新的计算任务
   */
  def statusUpdate(tid: Long, state: TaskState, serializedData: ByteBuffer) {
    var failedExecutor: Option[String] = None
    synchronized {
      try {
        //TaskState状态失去整个的执行者,所以记住它已经消失了
        if (state == TaskState.LOST && taskIdToExecutorId.contains(tid)) {
          // We lost this entire executor, so remember that it's gone         
          val execId = taskIdToExecutorId(tid)//根据taskId获得execId
          if (activeExecutorIds.contains(execId)) {
            removeExecutor(execId)//删除Executor
            failedExecutor = Some(execId)//标记失败execId
          }
        }
        taskIdToTaskSetManager.get(tid) match {
          case Some(taskSet) =>
            //如果Task的状态是任务成功完成
            if (TaskState.isFinished(state)) {
              taskIdToTaskSetManager.remove(tid) //删除此任务
              taskIdToExecutorId.remove(tid) //删除此任务
            }
            if (state == TaskState.FINISHED) {
              taskSet.removeRunningTask(tid) //删除此任务
              //处理执行任务成功的返回结果
              taskResultGetter.enqueueSuccessfulTask(taskSet, tid, serializedData)
            } else if (Set(TaskState.FAILED, TaskState.KILLED, TaskState.LOST).contains(state)) {
              //TaskSetManager标记任务已经结束,注意这里不一定是成功结束的
              taskSet.removeRunningTask(tid)
              //执行失败任务的返回结果
              taskResultGetter.enqueueFailedTask(taskSet, tid, state, serializedData)
            }
          case None =>
            logError(
              ("Ignoring update with state %s for TID %s because its task set is gone (this is " +
                "likely the result of receiving duplicate task finished status updates)")
                .format(state, tid))
        }
      } catch {
        case e: Exception => logError("Exception in statusUpdate", e)
      }
    }
    // Update the DAGScheduler without holding a lock on this, since that can deadlock
    //更新DAGScheduler而不用锁定,因为这可能会死锁
    if (failedExecutor.isDefined) {
      dagScheduler.executorLost(failedExecutor.get)
      backend.reviveOffers()
    }
  }

  /**
   * 接收所有分配给当前Drive Application的Executor的心跳,并将Task,Task计量信息,心跳等交给TaskSchedulerImpl和
   * DAGScheduler作进一进处理
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
    * 更新正在进行的任务的指标,让master知道BlockManager仍然存在,如果driver知道给定的块管理器,则返回true,否则返回false,表示块管理器应该重新注册。
   */
  override def executorHeartbeatReceived(
    execId: String,
    taskMetrics: Array[(Long, TaskMetrics)], // taskId -> TaskMetrics
    blockManagerId: BlockManagerId): Boolean = {
    //通过遍历TaskMetrics,依据taskIdToTaskSetId和activeTaskSets找到TaskSetManager
    val metricsWithStageIds: Array[(Long, Int, Int, TaskMetrics)] = synchronized {
      taskMetrics.flatMap {
        case (id, metrics) =>
          //找到TaskSetManager,然后将taskId,TaskSetManager.stageId,TaskSetManager.taskSet.attemptId,TaskMetrics
          //封装到类型为Array的数组
          taskIdToTaskSetManager.get(id).map { taskSetMgr =>
            (id, taskSetMgr.stageId, taskSetMgr.taskSet.stageAttemptId, metrics)
          }
      }
    }
    //用于更新Stage的各种测量数据.
    //blockManagerMaster持有blockManagerMasterActor发送BlockManagerHeartBeat消息到 BlockManagerMasterEndpoint   
    dagScheduler.executorHeartbeatReceived(execId, metricsWithStageIds, blockManagerId)
  }
/**
 * 对TaskSet中的任务信息进行成功标记
 */
  def handleTaskGettingResult(taskSetManager: TaskSetManager, tid: Long): Unit = synchronized {
    taskSetManager.handleTaskGettingResult(tid)
  }
  /**
   * 负责处理获取到的计算结果
   */
  def handleSuccessfulTask(
    taskSetManager: TaskSetManager,
    tid: Long,
    taskResult: DirectTaskResult[_]): Unit = synchronized {
    taskSetManager.handleSuccessfulTask(tid, taskResult)
  }
  /**
   * 对TaskSet中的任务信息进行成功状态标记
   */
  def handleFailedTask(
    taskSetManager: TaskSetManager,
    tid: Long,
    taskState: TaskState,
    reason: TaskEndReason): Unit = synchronized {
    //首先会调用taskSetManager来处理任务失败的情况,如果任务的失败数没有超过阈值,那么会重新提交任务
    taskSetManager.handleFailedTask(tid, taskState, reason)
    if (!taskSetManager.isZombie && taskState != TaskState.KILLED) {
      // Need to revive offers again now that the task set manager state has been updated to
      // reflect failed tasks that need to be re-run.
      //现在需要再次恢复提供,因为任务集管理器状态已更新,以反映需要重新运行的失败任务,
      backend.reviveOffers()
    }
  }

  def error(message: String) {
    synchronized {
      if (taskSetsByStageIdAndAttempt.nonEmpty) {
        // Have each task set throw a SparkException with the error
        //让每个任务集都抛出一个带有错误的SparkException
        for {
          attempts <- taskSetsByStageIdAndAttempt.values
          manager <- attempts.values
        } {
          try {
            manager.abort(message)
          } catch {
            case e: Exception => logError("Exception in error callback", e)
          }
        }
      } else {
        // No task sets are active but we still got an error. Just exit since this
        // must mean the error is during registration.
        //没有任务集是活动的,但我们仍然有错误,只是退出,因为这必须意味着错误是在注册期间,
        // It might be good to do something smarter here in the future.
        //在将来做更聪明的事情可能会很好
        throw new SparkException(s"Exiting due to error from cluster scheduler: $message")
      }
    }
  }

  override def stop() {
    speculationScheduler.shutdown()
    if (backend != null) {
      backend.stop()
    }
    if (taskResultGetter != null) {
      taskResultGetter.stop()
    }
    starvationTimer.cancel()
  }

  override def defaultParallelism(): Int = backend.defaultParallelism()

  // Check for speculatable tasks in all our active jobs.
  //检查我们所有活动工作中的可推测任务
  /**
   * 所谓的推测执行,就是当所有task都开始运行之后,Job Tracker会统计所有任务的平均进度,
   * 如果某个task所在的task node机器配置比较低或者CPU load很高(原因很多),
   * 导致任务执行比总体任务的平均执行要慢,此时Job Tracker会启动一个新的任务(duplicate task),
   * 原有任务和新任务哪个先执行完就把另外一个kill掉,
   */
  def checkSpeculatableTasks() {
    var shouldRevive = false
    synchronized {
      shouldRevive = rootPool.checkSpeculatableTasks()
    }
    if (shouldRevive) {
      backend.reviveOffers()
    }
  }
  /**
   * Executor执行丢失,可能Worker异常退出
   */
  override def executorLost(executorId: String, reason: ExecutorLossReason): Unit = {
    var failedExecutor: Option[String] = None

    synchronized {
      if (activeExecutorIds.contains(executorId)) {
        val hostPort = executorIdToHost(executorId)
        logError("Lost executor %s on %s: %s".format(executorId, hostPort, reason))
        removeExecutor(executorId)
        failedExecutor = Some(executorId)
      } else {
        // We may get multiple executorLost() calls with different loss reasons. For example, one
        // may be triggered by a dropped connection from the slave while another may be a report
        // of executor termination from Mesos. We produce log messages for both so we eventually
        // report the termination reason.
        //我们可能会以不同的丢失原因获得多个执行者Lost（）调用,例如，可能由从服务器的丢弃连接触发,
        // 而另一个可能是从Mesos终止执行器的报告, 我们生成日志消息，以便我们最终报告终止原因。
        logError("Lost an executor " + executorId + " (already removed): " + reason)
      }
    }
    // Call dagScheduler.executorLost without holding the lock on this to prevent deadlock
    //调用dagScheduler.executorLost而不用锁定这个来防止死锁
    if (failedExecutor.isDefined) {
      dagScheduler.executorLost(failedExecutor.get)
      backend.reviveOffers() //重新资源分配
    }
  }

  /** 
   *  Remove an executor from all our data structures and mark it as lost 
   *  从所有的数据结构中删除一个executor,并将其标记为丢失
   *  */
  private def removeExecutor(executorId: String) {
    activeExecutorIds -= executorId //从存集群删除当前可用的 executor id
    val host = executorIdToHost(executorId)
    val execs = executorsByHost.getOrElse(host, new HashSet)
    execs -= executorId
    if (execs.isEmpty) {
      executorsByHost -= host
      for (rack <- getRackForHost(host); hosts <- hostsByRack.get(rack)) {
        hosts -= host
        if (hosts.isEmpty) {
          hostsByRack -= rack
        }
      }
    }

    executorIdToHost -= executorId
    //将任务标记为丢失
    rootPool.executorLost(executorId, host)
  }
/**
 * 发送ExecutorAdded添加事件
 */
  def executorAdded(execId: String, host: String) {
    dagScheduler.executorAdded(execId, host)
  }

  def getExecutorsAliveOnHost(host: String): Option[Set[String]] = synchronized {
    executorsByHost.get(host).map(_.toSet)
  }
  //
  def hasExecutorsAliveOnHost(host: String): Boolean = synchronized {
    executorsByHost.contains(host)
  }
  //
  def hasHostAliveOnRack(rack: String): Boolean = synchronized {
    hostsByRack.contains(rack)
  }
  /**
   * isExecutorAlive就是判断参数中的 executor id 当前是否活动
   */
  def isExecutorAlive(execId: String): Boolean = synchronized {
    activeExecutorIds.contains(execId)
  }

  // By default, rack is unknown
  def getRackForHost(value: String): Option[String] = None
  //目的是为了等待backend就绪
  private def waitBackendReady(): Unit = {
    if (backend.isReady) {
      return
    }
    while (!backend.isReady) {
      synchronized {
        this.wait(100)
      }
    }
  }

  override def applicationId(): String = backend.applicationId()

  override def applicationAttemptId(): Option[String] = backend.applicationAttemptId()

  private[scheduler] def taskSetManagerForAttempt(
    stageId: Int,
    stageAttemptId: Int): Option[TaskSetManager] = {
    for {
      attempts <- taskSetsByStageIdAndAttempt.get(stageId)
      manager <- attempts.get(stageAttemptId)
    } yield {
      manager
    }
  }

}

private[spark] object TaskSchedulerImpl {
  /**
   * Used to balance containers across hosts.用于平衡主机间的容器
   *
   * Accepts a map of hosts to resource offers for that host, and returns a prioritized list of
   * resource offers representing the order in which the offers should be used.  The resource
   * offers are ordered such that we'll allocate one container on each host before allocating a
   * second container on any host, and so on, in order to reduce the damage if a host fails.
    *
    * 接受主机的Map,以提供该主机的资源优惠,并返回优先列出的资源提供,表示应使用优惠的顺序。
    * 资源提供被排序,以便我们在每个主机上分配一个容器,然后在任何主机上分配第二个容器,依此类推,以减少主机发生故障时的损坏。
   *
   * For example, given <h1, [o1, o2, o3]>, <h2, [o4]>, <h1, [o5, o6]>, returns
   * [o1, o5, o4, 02, o6, o3]
   */
  def prioritizeContainers[K, T](map: HashMap[K, ArrayBuffer[T]]): List[T] = {
    val _keyList = new ArrayBuffer[K](map.size)
    _keyList ++= map.keys

    // order keyList based on population of value in map
    //基于Map中的人口数量的顺序键列表
    val keyList = _keyList.sortWith(
      (left, right) => map(left).size > map(right).size)

    val retval = new ArrayBuffer[T](keyList.size * 2)
    var index = 0
    var found = true

    while (found) {
      found = false
      for (key <- keyList) {
        val containerList: ArrayBuffer[T] = map.get(key).getOrElse(null)
        assert(containerList != null)
        // Get the index'th entry for this host - if present
        //获取此主机的索引条目(如果存在)
        if (index < containerList.size) {
          retval += containerList.apply(index)
          found = true
        }
      }
      index += 1
    }

    retval.toList
  }

}
