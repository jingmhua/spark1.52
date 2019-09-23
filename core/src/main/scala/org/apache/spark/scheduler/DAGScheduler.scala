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

import java.io.NotSerializableException
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.Map
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, Stack}
import scala.concurrent.duration._
import scala.language.existentials
import scala.language.postfixOps
import scala.util.control.NonFatal

import org.apache.commons.lang3.SerializationUtils

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.partial.{ApproximateActionListener, ApproximateEvaluator, PartialResult}
import org.apache.spark.rdd.RDD
import org.apache.spark.rpc.RpcTimeout
import org.apache.spark.storage._
import org.apache.spark.util._
import org.apache.spark.storage.BlockManagerMessages.BlockManagerHeartbeat

/**
 * The high-level scheduling layer that implements stage-oriented scheduling. It computes a DAG of
 * stages for each job, keeps track of which RDDs and stage outputs are materialized, and finds a
 * minimal schedule to run the job. It then submits stages as TaskSets to an underlying
 * TaskScheduler implementation that runs them on the cluster.
  *
  * 实现面向阶段调度的高级调度层,它计算每个作业的阶段的DAG,跟踪哪些RDD和阶段输出被实现,并找到运行作业的最小时间表。
  * 然后,它将TaskSets的阶段提交到在集群上运行它们的基础TaskScheduler实现。
 *
 * In addition to coming up with a DAG of stages, this class also determines the preferred
 * locations to run each task on, based on the current cache status, and passes these to the
 * low-level TaskScheduler. Furthermore, it handles failures due to shuffle output files being
 * lost, in which case old stages may need to be resubmitted. Failures *within* a stage that are
 * not caused by shuffle file loss are handled by the TaskScheduler, which will retry each task
 * a small number of times before cancelling the whole stage.
 *
 * 根据当前缓存状态决定每个task的最佳位置，并将其传递到low-level的taskscheduler中
  * 除了提出DAG的阶段，这个班级也决定了首选基于当前缓存状态运行每个任务的位置,并将其传递到低级别的TaskScheduler。
  * 此外,它处理由于随机输出文件丢失而导致的故障,在这种情况下可能需要重新提交旧阶段。
  * 一个不是随机文件丢失引起的阶段的失败*由TaskScheduler处理,TaskScheduler将在取消整个阶段之前重试每个任务少量次数。
  *
 * Here's a checklist to use when making or reviewing changes to this class:
  * 制作或查看此类更改时使用的清单：
 *
 *  - When adding a new data structure, update `DAGSchedulerSuite.assertDataStructuresEmpty` to
 *    include the new structure. This will help to catch memory leaks.
  *    添加新数据结构时，请更新`DAGSchedulerSuite.assertDataStructuresEmpty`以包含新结构。 这将有助于捕获内存泄漏。
 */
private[spark]
/**
 * DAGScheduler主要用于在任务正式交给TaskSchedulerImpl提交之前做一些准备工作
 * 包括创建Job,将DAG中的RDD划分到不同的Stage,提交Stage等
 * 主要维护jobId和stageId的关系,stage,ActiveJob以及缓存的RDD的partitions的位置信息
 */
class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = new SystemClock())
  extends Logging {

  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
    this(
      sc,
      taskScheduler,
      sc.listenerBus,
      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
      sc.env.blockManager.master,
      sc.env)
  }

  def this(sc: SparkContext) = this(sc, sc.taskScheduler)

  private[scheduler] val metricsSource: DAGSchedulerSource = new DAGSchedulerSource(this)

  private[scheduler] val nextJobId = new AtomicInteger(0)
  private[scheduler] def numTotalJobs: Int = nextJobId.get()
  private val nextStageId = new AtomicInteger(0)
  //jobIdToStageIds存储的是jobId--stageIds的对应关系
  private[scheduler] val jobIdToStageIds = new HashMap[Int, HashSet[Int]]
  //stageIdToStage存储的是stageId-->Stage的对应关系 
  private[scheduler] val stageIdToStage = new HashMap[Int, Stage]
  private[scheduler] val shuffleToMapStage = new HashMap[Int, ShuffleMapStage]
  //将jobId-->ActiveJob的对应关系
  private[scheduler] val jobIdToActiveJob = new HashMap[Int, ActiveJob]
  //等待运行的调度Stage列表,防止过早执行
  // Stages we need to run whose parents aren't done
  private[scheduler] val waitingStages = new HashSet[Stage]

  // Stages we are running right now
  //正在运行的调度Stage列表,防止重复执行
  private[scheduler] val runningStages = new HashSet[Stage]
  //运行失败的调度Stage列表,需要重新执行,这里的设计是出于容错的考虑
  // Stages that must be resubmitted due to fetch failures
  private[scheduler] val failedStages = new HashSet[Stage]
  //将ActiveJob添加到HashSet类型的数据结构activeJobs中去
  private[scheduler] val activeJobs = new HashSet[ActiveJob]

  /**
   * 缓存每个partitions位置,key值是RDD ids,value值是由分区编号索引的数组,数组的每个值的设置位置RDD分区缓存。
   * Contains the locations that each RDD's partitions are cached on.  
   * This map's keys are RDD ids
   * and its values are arrays indexed by partition numbers. Each array value is the set of
   * locations where that RDD partition is cached.
    *
    * 包含每个RDD分区被缓存的位置。该Map的键是RDD ids,其值是由分区号索引的数组,
    * 每个数组值是RDD分区被缓存的位置集合。
   * All accesses to this map should be guarded by synchronizing on it (see SPARK-4454).
    *缓存的RDD的Partitions的位置信息
    */
  private val cacheLocs = new HashMap[Int, IndexedSeq[Seq[TaskLocation]]]

  // For tracking failed nodes, we use the MapOutputTracker's epoch number, which is sent with
  // every task. When we detect a node failing, we note the current epoch number and failed
  // executor, increment it for new tasks, and use this to ignore stray ShuffleMapTask results.

  // 对于跟踪失败的节点，我们使用MapOutputTracker的每个任务发送的时代号
  // 当我们检测到一个节点出现故障时,我们注意到当前的时代号和执行失败的执行者为新任务增加它,并使用它来忽略杂散ShuffleMapTask结果
  // TODO: Garbage collect information about failure epochs when we know there are no more
  //       stray messages to detect.
 //failedEpoch存储的是系统探测到的失效节点的集合,存储的是execId->失败次数对应关系
  private val failedEpoch = new HashMap[String, Long]

  private [scheduler] val outputCommitCoordinator = env.outputCommitCoordinator

  // A closure serializer that we reuse. 封装序列化器,我们重用
  // This is only safe because DAGScheduler runs in a single thread.
  //这只是安全的,因为DAGScheduler在一个线程中运行,
  private val closureSerializer = SparkEnv.get.closureSerializer.newInstance()

  /** If enabled, FetchFailed will not cause stage retry, in order to surface the problem.
    * 如果启用FetchFailed将不会导致阶段重试,以解决问题。*/
  private val disallowStageRetryForTest = sc.getConf.getBoolean("spark.test.noStageRetry", false)
    //线程池
  private val messageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("dag-scheduler-message")
   //主要职责处理DAGScheduler发各给它的各种消息
  private[scheduler] val eventProcessLoop = new DAGSchedulerEventProcessLoop(this)
  taskScheduler.setDAGScheduler(this)

  // Flag to control if reduce tasks are assigned preferred locations
  //如果减少任务分配的首选位置,则控制标志
  private val shuffleLocalityEnabled =
    sc.getConf.getBoolean("spark.shuffle.reduceLocality.enabled", false)
  // Number of map, reduce tasks above which we do not assign preferred locations
  // based on map output sizes. We limit the size of jobs for which assign preferred locations
  // as computing the top locations by size becomes expensive.
  //Map数量,减少我们不分配优先位置的任务基于地图输出大小,我们限制作业的大小,其分配优先位置作为按大小计算顶部位置变得昂贵
  private[this] val SHUFFLE_PREF_MAP_THRESHOLD = 1000
  // NOTE: This should be less than 2000 as we use HighlyCompressedMapStatus beyond that
  //注意：这个值应该小于2000，因为我们使用的是HighlyCompressedMapStatus
  private[this] val SHUFFLE_PREF_REDUCE_THRESHOLD = 1000

  // Fraction of total map output that must be at a location for it to considered as a preferred
  // location for a reduce task.
  //总Map输出的分数必须位于某个位置，以将其视为缩减任务的首选位置。
  // Making this larger will focus on fewer locations where most data can be read locally, but
  // may lead to more delay in scheduling if those locations are busy.
  //使这个更大的重点将放在更少的位置,大多数数据可以在本地读取,但是如果这些位置很忙,可能会导致更多的调度延迟。
  private[scheduler] val REDUCER_PREF_LOCS_FRACTION = 0.2

  /**
   * Called by the TaskSetManager to report task's starting.
    * 由TaskSetManager调用来报告任务的开始
   */
  def taskStarted(task: Task[_], taskInfo: TaskInfo) {
    eventProcessLoop.post(BeginEvent(task, taskInfo))
  }

  /**
   * Called by the TaskSetManager to report that a task has completed
   * and results are being fetched remotely.
    * 由TaskSetManager调用以报告任务已完成，并且远程获取结果
   */
  def taskGettingResult(taskInfo: TaskInfo) {
    eventProcessLoop.post(GettingResultEvent(taskInfo))
  }

  /**
   * Called by the TaskSetManager to report task completions or failures.
   * 调用TaskSetManager报告Task完成或者失败
   */
  def taskEnded(
      task: Task[_],
      reason: TaskEndReason,
      result: Any,
      accumUpdates: Map[Long, Any],
      taskInfo: TaskInfo,
      taskMetrics: TaskMetrics): Unit = {
    //DAGSchedulerEventProcessLoop接收CompletionEvent消息,将处理交给CompletionEvent
    eventProcessLoop.post(
      CompletionEvent(task, reason, result, accumUpdates, taskInfo, taskMetrics))
  }

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
    *
    * 更新正在进行的任务的指标,让master知道BlockManager仍然存在,如果driver知道给定的块管理器,
    * 则返回true,否则返回false,表示块管理器应该重新注册。
   * 
   */
  def executorHeartbeatReceived(
      execId: String,
      taskMetrics: Array[(Long, Int, Int, TaskMetrics)], // (taskId, stageId, stateAttempt, metrics)
      blockManagerId: BlockManagerId): Boolean = {
    //更新Stage的各中测量数据
    listenerBus.post(SparkListenerExecutorMetricsUpdate(execId, taskMetrics))
    //blockManagerMaster持有blockManagerMasterActor发送BlockManagerHeartBeat消息
    //Executor启动的时候向Drive发送BlockManagerHeartbeat心跳
    blockManagerMaster.driverEndpoint.askWithRetry[Boolean](
      BlockManagerHeartbeat(blockManagerId), new RpcTimeout(600 seconds, "BlockManagerHeartbeat"))
  }

  /**
   * Called by TaskScheduler implementation when an executor fails.  
   * 调用执行任务失败
   */
  def executorLost(execId: String): Unit = {
    eventProcessLoop.post(ExecutorLost(execId))
  }

  /**
   * Called by TaskScheduler implementation when a host is added.  
   * 添加主机时调用任务调试器
   */
  def executorAdded(execId: String, host: String): Unit = {
    eventProcessLoop.post(ExecutorAdded(execId, host))
  }

  /**
   * Called by the TaskSetManager to cancel an entire TaskSet due to either repeated failures or
   * cancellation of the job itself.
    * 由TaskSetManager调用由于重复失败或取消作业本身而取消整个TaskSet
   */
  def taskSetFailed(taskSet: TaskSet, reason: String, exception: Option[Throwable]): Unit = {
    eventProcessLoop.post(TaskSetFailed(taskSet, reason, exception))
  }

  private[scheduler]
  def getCacheLocs(rdd: RDD[_]): IndexedSeq[Seq[TaskLocation]] = cacheLocs.synchronized {
    // Note: this doesn't use `getOrElse()` because this method is called O(num tasks) times
    //注意：这不使用`getOrElse（）`，因为这个方法被称为O（num tasks）次
    if (!cacheLocs.contains(rdd.id)) {
      // Note: if the storage level is NONE, we don't need to get locations from block manager.
      //注意：如果没有设置存储级别,不需要从块管理器获得到的位置
      val locs: IndexedSeq[Seq[TaskLocation]] = if (rdd.getStorageLevel == StorageLevel.NONE) {
        //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
        IndexedSeq.fill(rdd.partitions.length)(Nil)
      } else {
        val blockIds =
          //indices返回所有有效索引值
          rdd.partitions.indices.map(index => RDDBlockId(rdd.id, index)).toArray[BlockId]
        //根据块id从blockManagerMaster获得Task位置
        (blockManagerMaster getLocations blockIds).map { bms =>
          bms.map(bm => TaskLocation(bm.host, bm.executorId))
        }
      }
      cacheLocs(rdd.id) = locs
    }
    cacheLocs(rdd.id)
  }

  private def clearCacheLocs(): Unit = cacheLocs.synchronized {
    cacheLocs.clear()
  }

  /**
   * Get or create a shuffle map stage for the given shuffle dependency's map side.   
   * 针对给定的shuffle 依赖的map端,获取或者创建一个ShuffleMapStage 
   */
  private def getShuffleMapStage(
      shuffleDep: ShuffleDependency[_, _, _],
      firstJobId: Int): ShuffleMapStage = {
    /**
     * 从数据结构shuffleToMapStage中根据shuffleId获取,如果有直接返回,否则  
     * 获取ShuffleDependency中的rdd,调用getAncestorShuffleDependencies()方法,  
     * 循环每个parent,调用newOrUsedShuffleStage()方法,创建一个新的ShuffleMapStage,  
     * 并加入到数据结构shuffleToMapStage中去  
     */
    shuffleToMapStage.get(shuffleDep.shuffleId) match {
      case Some(stage) => stage //如果直接返回
      case None =>
        //看是否该stage的parent Stage已经生成,如果没有,则生成它们        
        // We are going to register ancestor shuffle dependencies        
        registerShuffleDependencies(shuffleDep, firstJobId)
        // Then register current shuffleDep
        //生成当前RDD所在的Stage
        val stage = newOrUsedShuffleStage(shuffleDep, firstJobId)
        //并加入到数据结构shuffleToMapStage中去  
        shuffleToMapStage(shuffleDep.shuffleId) = stage
        stage
    }
  }

  /**
   * Helper function to eliminate some code re-use when creating new stages.
    * 助手功能在创建新阶段时消除一些代码重用
   */
  private def getParentStagesAndId(rdd: RDD[_], firstJobId: Int): (List[Stage], Int) = {
    //获取所有的父Stage的列表,父Stage主要是宽依赖对应的Stage
    val parentStages = getParentStages(rdd, firstJobId)
    //获取下一个stageId,为AtomicInteger类型,getAndIncrement()能保证原子操作  
    val id = nextStageId.getAndIncrement()
    // 返回parentStages和id  
    (parentStages, id)
  }

  /**
   * 创建一个ShuffleMapStage
   * Create a ShuffleMapStage as part of the (re)-creation of a shuffle map stage in
   * newOrUsedShuffleStage.  The stage will be associated with the provided firstJobId.
   * Production of shuffle map stages should always use newOrUsedShuffleStage, not
   * newShuffleMapStage directly.
    *
    * 创建一个ShuffleMapStage作为（重新）在newOrUsedShuffleStage中重新组合一个洗牌地图阶段的一部分,stage将与提供的firstJobId相关联。
    * 洗牌Map阶段的生产应该始终使用newOrUsedShuffleStage,而不是newShuffleMapStage。
   */
  private def newShuffleMapStage(
      rdd: RDD[_],
      numTasks: Int,
      shuffleDep: ShuffleDependency[_, _, _],
      firstJobId: Int,
      callSite: CallSite): ShuffleMapStage = {
    // 获得所有的父Stage和stage Id,父Stage主要是宽依赖对应的Stage
    val (parentStages: List[Stage], id: Int) = getParentStagesAndId(rdd, firstJobId)
     // 创建一个ShuffleMapStage  
    val stage: ShuffleMapStage = new ShuffleMapStage(id, rdd, numTasks, parentStages,
      firstJobId, callSite, shuffleDep)
    //将stage和stage Id注册到stageIdToStage(HashMap)
    stageIdToStage(id) = stage
    //更新Stage及祖先Stage与jobId对应关系
    updateJobIdStageIdMaps(firstJobId, stage)
    stage
  }

  /**
   * Create a ResultStage associated with the provided jobId.
   * 提供一个jobid创建一个相关resultstage
   */
  private def newResultStage(
      rdd: RDD[_],
      numTasks: Int,
      jobId: Int,
      callSite: CallSite): ResultStage = {
    //获取所有的父Stage列表,父Stage主要是宽依赖对应的Stage,id是StageID
    val (parentStages: List[Stage], id: Int) = getParentStagesAndId(rdd, jobId)
    //创建Stage
    val stage: ResultStage = new ResultStage(id, rdd, numTasks, parentStages, jobId, callSite)
    //将stage加入到stageIdToStage中  
    stageIdToStage(id) = stage
    //更新数据结构jobIdToStageIds  
    updateJobIdStageIdMaps(jobId, stage)
    //返回stage  
    stage
  }

  /**
   * Create a shuffle map Stage for the given RDD.  The stage will also be associated with the
   * provided firstJobId.  If a stage for the shuffleId existed previously so that the shuffleId is
   * present in the MapOutputTracker, then the number and location of available outputs are
   * recovered from the MapOutputTracker
    *
    * 为给定的RDD创建一个shuffle映射阶段,stage也将与提供的firstJobId相关联,如果shuffleId的一个阶段先前存在，
    * 以便shOleleId存在于MapOutputTracker中,则可以从MapOutputTracker中恢复可用输出的数量和位置
    *
   * 根据RDD创建一个宽依赖Stage,这个Stage将提供相关firstJobId,如果这个宽依赖 Stage存在则MapOutputTracker查找
   * 生成一个stage,不过如果这个stage已存在,那么将恢复这个stage的结果,从而避免了重复计算
   */
  private def newOrUsedShuffleStage(
      shuffleDep: ShuffleDependency[_, _, _],
      firstJobId: Int): ShuffleMapStage = {
    //从shuffleDep中获取RD
    val rdd = shuffleDep.rdd
    //获取RDD的分区个数,即未来的task数目  
    val numTasks = rdd.partitions.length
     // 构造一个ShuffleMapStage实例  
    val stage = newShuffleMapStage(rdd, numTasks, shuffleDep, firstJobId, rdd.creationSite)
    
    if (mapOutputTracker.containsShuffle(shuffleDep.shuffleId)) {
      //如果mapOutputTracker中存在   
      //根据shuffleId从mapOutputTracker中获取序列化的多个MapOutputStatus对象  
      val serLocs = mapOutputTracker.getSerializedMapOutputStatuses(shuffleDep.shuffleId)
      //反序列化 
      val locs = MapOutputTracker.deserializeMapStatuses(serLocs)
       //循环  
      for (i <- 0 until locs.length) {
        //计算结果复制到Stage
        stage.outputLocs(i) = Option(locs(i)).toList // locs(i) will be null if missing
      }
      //保存Stage可用结果的数,对于不可用的部分,会被重新计算
      stage.numAvailableOutputs = locs.count(_ != null)
    } else {
      // 如果mapOutputTracker中不存在,注册一个  
      // Kind of ugly: need to register RDDs with the cache and map output tracker here
      // since we can't do it in the RDD constructor because # of partitions is unknown     
      //shuffleMapTask的计算结果都会传递给Driver端的mapOutputTracker,其他的Task可以通过查询它来获取这些结果
      logInfo("Registering RDD " + rdd.id + " (" + rdd.getCreationSite + ")")
      //向mapOutputTracker中注册该stage
      // 注册的内容为  
      // 1、根据shuffleDep获取的shuffleId；  
      // 2、rdd中分区的个数  
      mapOutputTracker.registerShuffle(shuffleDep.shuffleId, rdd.partitions.length)
    }
    stage
  }

  /**
   * Get or create the list of parent stages for a given RDD.  The new Stages will be created with
   * the provided firstJobId.
   * 获取所有的父Stage的列表,父Stage主要是宽依赖对应的Stage
   * Spark中Job会被划分为一到多个Stage,这些Stage的划分是从finalStage开始,从后往前边划分创建的
   * 
   * getParentStages用于获取或者创建给定RDD的所有父Stage,这些Stage将被分配给job对应的job
   */
  private def getParentStages(rdd: RDD[_], firstJobId: Int): List[Stage] = {
    val parents = new HashSet[Stage] //存储parent stage,HashSet为了防止里面元素重复 
    val visited = new HashSet[RDD[_]] //用HashSet存储已经被访问过的RDD  
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    //存储需要被处理的RDD,Stack中得RDD都需要被处理
    val waitingForVisit = new Stack[RDD[_]]//堆,后进先出的原则存储数据
    // 定义一个visit函数,根据传入的RDD,如果之前没有处理过,标记为已处理,循环此RDD的依赖关系dependencies  
    // 如果是ShuffleDependency,获取其parents；如果不是,则说明为同一stage,并压入Stack：waitingForVisit顶部 
    def visit(r: RDD[_]) {//广度优先遍历Rdd生成的依赖树
      if (!visited(r)) {//如果之前没有处理过
        //将RDD r加入到visited,表示已经处理过了  
        visited += r//标记为已处理
        // Kind of ugly: need to register RDDs with the cache here since
        // we can't do it in its constructor because # of partitions is unknown
        //种丑：需要在缓存中注册RDD,因为我们不能在其构造函数中执行它,因为＃分区是未知的
        //循环Rdd r的依赖关系  
        for (dep <- r.dependencies) {//逐个处理当前RDD依赖的Parent RDD,通过调用dependencies获取RDD所有的dependencies序列
          dep match {//逐个访问每个RDD及其依赖的非Shuffle的RDD,遍历每个RDD的ShuffleDependency依赖
            
            case shufDep: ShuffleDependency[_, _, _] =>
               //如果是ShuffleDependency,获取其parents,添加到parents中去  
              parents += getShuffleMapStage(shufDep, firstJobId)//并调用getShuffleMapStage获取或者创建Stage,并将这些返回的Stage都放入parents
            case _ =>
              //否则,属于同一个stage,压入Stack顶部,后续再递归处理  
              waitingForVisit.push(dep.rdd)
          }
        }
      }
    }
    //将rdd压入Stack顶部  
    waitingForVisit.push(rdd)//堆,后进先出的原则存储数据
    //循环waitingForVisit,弹出每个rdd
    while (waitingForVisit.nonEmpty) {//只要Stack不为空,则一直处理
      //调用visit()方法,处理每个rdd  
      visit(waitingForVisit.pop())
    }
    //返回得到的parents列表  
    parents.toList
  }

  /** 
   *  Find ancestor missing shuffle dependencies and register into shuffleToMapStage 
   *  负责确认该Stage的parent Stage是否已经生成,如果没有则生成它们
   *  */
  private def registerShuffleDependencies(shuffleDep: ShuffleDependency[_, _, _], firstJobId: Int) {
    //首先获取没有生成Stage的ShuffleDependency
    val parentsWithNoMapStage = getAncestorShuffleDependencies(shuffleDep.rdd)
    while (parentsWithNoMapStage.nonEmpty) {
      val currentShufDep = parentsWithNoMapStage.pop()
      //根据ShuffleDependenc来生成Stage,如果Stage已经存在,那么恢复这个Stage的结果,从而避免了重复计算
      val stage = newOrUsedShuffleStage(currentShufDep, firstJobId)
      shuffleToMapStage(currentShufDep.shuffleId) = stage
    }
  }

  /** Find ancestor shuffle dependencies that are not registered in shuffleToMapStage yet */
  //实现和getParentStage差不多,只不过它遇到ShuffleDependency时首先会判断Stage是否已经存,不存在则把这个
  //依赖作为返回值的一个元素,由调用者来完成Stage的创建
  private def getAncestorShuffleDependencies(rdd: RDD[_]): Stack[ShuffleDependency[_, _, _]] = {
    //存放parents的栈:Stack  
    val parents = new Stack[ShuffleDependency[_, _, _]]
    //存放已经处理过的RDD的哈希表：HashSet  
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    //存放等待调用visit的RDD的栈：Stack  
    val waitingForVisit = new Stack[RDD[_]]
    // 定义方法visit()
    def visit(r: RDD[_]) {
      if (!visited(r)) {// 如果之前没有处理过  
        visited += r// 标记为已处理  
         // 循环RDD的所有依赖  
        for (dep <- r.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] => // 如果是ShuffleDependency
               // 如果shuffleToMapStage中没有,添加到parents中  
              if (!shuffleToMapStage.contains(shufDep.shuffleId)) {
                parents.push(shufDep)
              }
              // 将该dependence的rdd压入waitingForVisit栈顶部  
              waitingForVisit.push(shufDep.rdd)
            case _ =>
              waitingForVisit.push(dep.rdd)
          }
        }
      }
    }
    //将RDD压入到waitingForVisit顶部 
    waitingForVisit.push(rdd)
     //循环waitingForVisit,针对每个RDD调用visit()方法  
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
    parents
  }
/**
 * 获取父阶段Stage,未完成任务列表
 */
  private def getMissingParentStages(stage: Stage): List[Stage] = {
    //存储尚未提交的parent stages,用于最后结果的返回  
    val missing = new HashSet[Stage]
    // 已被处理的RDD集合
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    //我们在这里手动维护堆栈,以防止由递归访问引起的StackOverflowError
    // 待处理RDD栈,后入先出  
    val waitingForVisit = new Stack[RDD[_]]//后进先出"(LIFO) 的数据结构
    def visit(rdd: RDD[_]) { // 定义函数visit  
      if (!visited(rdd)) { // 通过visited判断rdd是否已处理  
        visited += rdd  // 添加到visited,下次不会再处理
        //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
        val rddHasUncachedPartitions = getCacheLocs(rdd).contains(Nil)
        if (rddHasUncachedPartitions) {
          for (dep <- rdd.dependencies) {  // 循环rdd的dependencies  
            dep match {  // 宽依赖  
              case shufDep: ShuffleDependency[_, _, _] =>
                 // 调用getShuffleMapStage,获取ShuffleMapStage  
                val mapStage = getShuffleMapStage(shufDep, stage.firstJobId)
                if (!mapStage.isAvailable) {//判断Stage是否可用
                  missing += mapStage
                }
              case narrowDep: NarrowDependency[_] =>  // 窄依赖,直接将RDD压入waitingForVisit栈  
                waitingForVisit.push(narrowDep.rdd)
            }
          }
        }
      }
    }
     //将stage的rdd压入到waitingForVisit顶部  
    waitingForVisit.push(stage.rdd)
     //循环处理waitingForVisit,对弹出的每个rdd调用函数visit  
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
     //返回stage列表  
    missing.toList
  }

  /**
   * Registers the given jobId among the jobs that need the given stage and
   * all of that stage's ancestors.
   * 最终将jobId添加到Stage及它的所有祖先Stage的映射,将job和Stage及它的所有祖先Stage的 ID
   * 更新到jobIdToStageids中
   *
   */
  private def updateJobIdStageIdMaps(jobId: Int, stage: Stage): Unit = {
    //定义一个函数updateJobIdStageIdMapsList()
    def updateJobIdStageIdMapsList(stages: List[Stage]) {
      if (stages.nonEmpty) {
        // 获取列表头stages元素  
        val s = stages.head//取出第一个stages,列表里的头元素,即第一个元素
        s.jobIds += jobId//把当前Job,添加到stages.jobIds(HashSet)
        //将job id和祖先Stag ID 更新到jobIdToStageids中
        jobIdToStageIds.getOrElseUpdate(jobId, new HashSet[Int]()) += s.id    
        //获取所有的父Stage的列表,父Stage主要是宽依赖对应的Stage
        val parents: List[Stage] = getParentStages(s.rdd, jobId)
        //当前Stage不包含JobId
        val parentsWithoutThisJobId = parents.filter { ! _.jobIds.contains(jobId) }
        updateJobIdStageIdMapsList(parentsWithoutThisJobId ++ stages.tail)//tail返回除头元素外的剩余元素组成的列表
      }
    }
    //调用函数updateJobIdStageIdMapsList()
    updateJobIdStageIdMapsList(List(stage))
  }

  /**
   * Removes state for job and any stages that are not needed by any other job.  Does not
   * handle cancelling tasks or notifying the SparkListener about finished jobs/stages/tasks.
    * 删除任何其他Job所不需要的工作状态和任何Stage,不处理取消任务或通知SparkListener关于完成的作业/阶段/任务
   * @param job The job whose state to cleanup.
   */
  private def cleanupStateForJobAndIndependentStages(job: ActiveJob): Unit = {
    val registeredStages = jobIdToStageIds.get(job.jobId)
    if (registeredStages.isEmpty || registeredStages.get.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    } else {
      stageIdToStage.filterKeys(stageId => registeredStages.get.contains(stageId)).foreach {
        case (stageId, stage) =>
          val jobSet = stage.jobIds
          if (!jobSet.contains(job.jobId)) {
            logError(
              "Job %d not registered for stage %d even though that stage was registered for the job"
              .format(job.jobId, stageId))
          } else {
            def removeStage(stageId: Int) {
              // data structures based on Stage
              for (stage <- stageIdToStage.get(stageId)) {
                if (runningStages.contains(stage)) {
                  logDebug("Removing running stage %d".format(stageId))
                  runningStages -= stage
                }
                for ((k, v) <- shuffleToMapStage.find(_._2 == stage)) {
                  shuffleToMapStage.remove(k)
                }
                if (waitingStages.contains(stage)) {
                  logDebug("Removing stage %d from waiting set.".format(stageId))
                  waitingStages -= stage
                }
                if (failedStages.contains(stage)) {
                  logDebug("Removing stage %d from failed set.".format(stageId))
                  failedStages -= stage
                }
              }
              // data structures based on StageId
              stageIdToStage -= stageId
              logDebug("After removal of stage %d, remaining stages = %d"
                .format(stageId, stageIdToStage.size))
            }

            jobSet -= job.jobId
            //没有其他工作需要这个阶段
            if (jobSet.isEmpty) { // no other job needs this stage
              removeStage(stageId)
            }
          }
      }
    }
    jobIdToStageIds -= job.jobId
    jobIdToActiveJob -= job.jobId
    activeJobs -= job
    job.finalStage.resultOfJob = None
  }

  /**
   * Submit a job to the job scheduler and get a JobWaiter object back. The JobWaiter object
   * can be used to block until the the job finishes executing or can be used to cancel the job.
   * 将作业提交到作业调度器和得到一个jobwaiter对象,JobWaiter对象可以用来堵塞直到Job执行结束或可取消Job
   */
  def submitJob[T, U](
      rdd: RDD[T],//job运行的RDD
      func: (TaskContext, Iterator[T]) => U,//作用于RDD上的函数
      partitions: Seq[Int],//需要处理的分区索引
      callSite: CallSite,//用户代码中调用spark接口的堆栈信息
      resultHandler: (Int, U) => Unit,//结果处理函数
      properties: Properties): JobWaiter[U] = {
    // Check to make sure we are not launching a task on a partition that does not exist.
    //调用RDD.partitions函数获取 当前Job最大分区数,即maxPartitions
    val maxPartitions = rdd.partitions.length
    //根据maxPartitions确认我们没有在一个不存在的partition上运行任务
    partitions.find(p => p >= maxPartitions || p < 0).foreach { p =>
      throw new IllegalArgumentException(
        "Attempting to access a non-existent partition: " + p + ". " +
          "Total number of partitions: " + maxPartitions)
    }
    //为Job生成一个jobId,jobId为AtomicInteger类型,getAndIncrement()确保了原子操作性,每次生成后都自增  
    val jobId = nextJobId.getAndIncrement()
    //如果partitions大小为0,即没有需要执行任务的分区,快速返回  
    if (partitions.size == 0) {
      return new JobWaiter[U](this, jobId, 0, resultHandler)
    }

    assert(partitions.size > 0)
    //func转化下,否则JobSubmitted无法接受这个func参数,T转变为_  
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    // 创建一个JobWaiter对象 用来监听Job执行情况
    val waiter = new JobWaiter(this, jobId, partitions.size, resultHandler)
    //eventProcessLoop加入一个JobSubmitted事件到事件队列中  
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions.toArray, callSite, waiter,
      SerializationUtils.clone(properties)))
      //返回waiter
    waiter
  }
  //JOb的提交,这个方法是一个阻塞方法,会在Job完成之前一直阻塞等待,直到Job执行完成之后返回所得的结果
  def runJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      resultHandler: (Int, U) => Unit,
      properties: Properties): Unit = {
    val start = System.nanoTime //开始时间
    /***
     * 调用submitJob()方法,提交Job,返回JobWaiter
     *  rdd为最后一个rdd,即target RDD to run tasks on  
     *  func为该rdd上每个分区需要执行的函数
     *  partitions为该rdd上需要执行操作的分区集合
     *  callSite为用户程序job被调用的地方  
     *  resultHandler 结果处理函数
     */
    val waiter = submitJob(rdd, func, partitions, callSite, resultHandler, properties)
    //awaitResult()一直阻塞等待,直到Job执行完成之后返回所得的结果
    waiter.awaitResult() match {
      case JobSucceeded =>
        logInfo("Job %d finished: %s, took %f s".format//1e9就为1*(10的九次方),也就是十亿
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
      case JobFailed(exception: Exception) =>
        logInfo("Job %d failed: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
        // SPARK-8644: Include user stack trace in exceptions coming from DAGScheduler.
        //在来自DAGScheduler的异常中包含用户堆栈跟踪
        //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
        val callerStackTrace = Thread.currentThread().getStackTrace.tail
        exception.setStackTrace(exception.getStackTrace ++ callerStackTrace)
        throw exception
    }
  }
  //近似估计
  def runApproximateJob[T, U, R](
      rdd: RDD[T],//job运行的RDD
      func: (TaskContext, Iterator[T]) => U,//作用于RDD上的函数
      evaluator: ApproximateEvaluator[U, R],//结果近似计算与评价的实现
      callSite: CallSite,//用户代码中调用spark接口的堆栈信息
      timeout: Long,//超时时间
      properties: Properties): PartialResult[R] = {//PartialResult 返回值部分结果
    val listener = new ApproximateActionListener(rdd, func, evaluator, timeout)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val partitions = (0 until rdd.partitions.length).toArray
    val jobId = nextJobId.getAndIncrement()
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions, callSite, listener, SerializationUtils.clone(properties)))
      //如果作业失败,将抛出异常
    listener.awaitResult()    // Will throw an exception if the job fails
  }

  /**
   * Cancel a job that is running or waiting in the queue.
   * 取消正在运行或在队列中等待的作业
   */
  def cancelJob(jobId: Int): Unit = {
    logInfo("Asked to cancel job " + jobId)
    eventProcessLoop.post(JobCancelled(jobId))
  }

  def cancelJobGroup(groupId: String): Unit = {
    logInfo("Asked to cancel job group " + groupId)
    eventProcessLoop.post(JobGroupCancelled(groupId))
  }

  /**
   * Cancel all jobs that are running or waiting in the queue.
   * 取消正在队列中运行或等待的所有工作
   */
  def cancelAllJobs(): Unit = {
    eventProcessLoop.post(AllJobsCancelled)
  }

  private[scheduler] def doCancelAllJobs() {
    // Cancel all running jobs.
    // 取消所有运行作业
    runningStages.map(_.firstJobId).foreach(handleJobCancellation(_,
      reason = "as part of cancellation of all jobs"))
    // These should already be empty by this point,
    activeJobs.clear() //清空正在运行的Job
    jobIdToActiveJob.clear() // but just in case we lost track of some jobs...
    submitWaitingStages()
  }

  /**
   * Cancel all jobs associated with a running or scheduled stage.
   * 取消正在运行或调度阶段相关联的所有作业(Job)
   */
  def cancelStage(stageId: Int) {
    eventProcessLoop.post(StageCancelled(stageId))
  }

  /**
   * Resubmit any failed stages. Ordinarily called after a small amount of time has passed since
   * the last fetch failure.
   * 重新提交任何失败的阶段,自从上次提取失败以来,通常调用了很少的时间。
   */
  private[scheduler] def resubmitFailedStages() {
    if (failedStages.size > 0) {
      // Failed stages may be removed by job cancellation, so failed might be empty even if
      // the ResubmitFailedStages event has been scheduled.
      //失败的阶段可能会由于取消工作而被删除，所以即使失败也可能是空的ResubmitFailedStages事件已经安排
      logInfo("Resubmitting failed stages")
      clearCacheLocs()
      val failedStagesCopy = failedStages.toArray
      failedStages.clear()
      for (stage <- failedStagesCopy.sortBy(_.firstJobId)) {
        submitStage(stage)
      }
  }
    submitWaitingStages()
  }

  /**
   * Check for waiting or failed stages which are now eligible for resubmission.
   * Ordinarily run on every iteration of the event loop.
   * 用于将跟踪失败的节点重新恢复正常和提交等待中的Stage
   */
  private def submitWaitingStages() {
    // TODO: We might want to run this less often, when we are sure that something has become
    // runnable that wasn't before.
    logTrace("Checking for newly runnable parent stages")
    logTrace("running: " + runningStages)
    logTrace("waiting: " + waitingStages)
    logTrace("failed: " + failedStages)
     // 将waitingStages转换为数组  
    val waitingStagesCopy = waitingStages.toArray
    // 清空waitingStages  
    waitingStages.clear()//实际上循环waitingStages中的Stage并调用submitStage
    // 循环waitingStagesCopy,挨个调用submitStage()方法进行提交  
    for (stage <- waitingStagesCopy.sortBy(_.firstJobId)) {
      submitStage(stage)
    }
  }

  /** 
   *  Finds the earliest-created active job that needs the stage 
   *  查找Stage保存活动Job
   *  */
  // TODO: Probably should actually find among the active jobs that need this
  // stage the one with the highest priority (highest-priority pool, earliest created).
  // That should take care of at least part of the priority inversion problem with
  // cross-job dependencies.
  //优先级最高(最高优先级池,最早创建的),这应该在交叉作业依赖关系中处理至少部分优先级反转问题。
  private def activeJobForStage(stage: Stage): Option[Int] = {
    //查找Stage保存活动Job转换数组并按升级排序
    val jobsThatUseStage: Array[Int] = stage.jobIds.toArray.sorted
    //jobIdToActiveJob包含(Job)任务
    jobsThatUseStage.find(jobIdToActiveJob.contains)
  }

  private[scheduler] def handleJobGroupCancelled(groupId: String) {
    // Cancel all jobs belonging to this job group.取消属于此作业组的所有作业
    // First finds all active jobs with this group id, and then kill stages for them.
    //首先找到具有该组ID的所有活动作业,然后为它们杀死阶段
    val activeInGroup = activeJobs.filter { activeJob =>
      Option(activeJob.properties).exists {
        _.getProperty(SparkContext.SPARK_JOB_GROUP_ID) == groupId
      }
    }
    val jobIds = activeInGroup.map(_.jobId)
    jobIds.foreach(handleJobCancellation(_, "part of cancelled job group %s".format(groupId)))
    submitWaitingStages()
  }

  private[scheduler] def handleBeginEvent(task: Task[_], taskInfo: TaskInfo) {
    // Note that there is a chance that this task is launched after the stage is cancelled.
    //请注意,在取消stage后,有可能启动此任务。
    // In that case, we wouldn't have the stage anymore in stageIdToStage.
    //在这种情况下,我们在stageIdToStage中不再有stage了
    val stageAttemptId = stageIdToStage.get(task.stageId).map(_.latestInfo.attemptId).getOrElse(-1)
    listenerBus.post(SparkListenerTaskStart(task.stageId, stageAttemptId, taskInfo))
    submitWaitingStages()
  }

  private[scheduler] def handleTaskSetFailed(
      taskSet: TaskSet,
      reason: String,
      exception: Option[Throwable]): Unit = {
    stageIdToStage.get(taskSet.stageId).foreach { abortStage(_, reason, exception) }
    submitWaitingStages()
  }

  private[scheduler] def cleanUpAfterSchedulerStop() {
    for (job <- activeJobs) {
      val error = new SparkException("Job cancelled because SparkContext was shut down")
      job.listener.jobFailed(error)
      // Tell the listeners that all of the running stages have ended.  Don't bother
      // cancelling the stages because if the DAG scheduler is stopped, the entire application
      // is in the process of getting stopped.
      //告诉听众所有的运行阶段已经结束,不要打扰取消阶段,因为如果DAG调度程序停止,整个应用程序正在停止。
      val stageFailedMessage = "Stage cancelled because SparkContext was shut down"
      // The `toArray` here is necessary so that we don't iterate over `runningStages` while
      // mutating it.
      //这里的`toArray`是必要的,所以我们不会在突破'runStages'的过程中迭代
      runningStages.toArray.foreach { stage =>
        markStageAsFinished(stage, Some(stageFailedMessage))
      }
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  private[scheduler] def handleGetTaskResult(taskInfo: TaskInfo) {
    listenerBus.post(SparkListenerTaskGettingResult(taskInfo))
    submitWaitingStages()
  }
/**
 * 处理提交的Job,调用划分Stage方法
 */
  private[scheduler] def handleJobSubmitted(jobId: Int,
      finalRDD: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      callSite: CallSite,
      listener: JobListener,
      properties: Properties) {
    var finalStage: ResultStage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      //如果例如,在HadoopRDD上运行其基础HDFS文件已被删除的新作阶段创建可能会引发异常
      //根据RDD创建finalStage,
      finalStage = newResultStage(finalRDD, partitions.length, jobId, callSite)
    } catch {
      case e: Exception =>
        //在创建的时候可能会出现异常：HDFS文件被修改,或者被删除了
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }
    //创建ActiveJob,准备计算这个finalStage
    if (finalStage != null) {
      //ActiveJob对象用来表示这个活跃的Job,然后提交ResultStage
      val job = new ActiveJob(jobId, finalStage, func, partitions, callSite, listener, properties)
      // 清除RDD分区位置缓存 
      // private val cacheLocs = new HashMap[Int, IndexedSeq[Seq[TaskLocation]]]  
      clearCacheLocs()
       //调用logInfo()方法记录日志信息  
      logInfo("Got job %s (%s) with %d output partitions".format(
        job.jobId, callSite.shortForm, partitions.length))
      logInfo("Final stage: " + finalStage + "(" + finalStage.name + ")")
      logInfo("Parents of final stage: " + finalStage.parents)
      logInfo("Missing parents: " + getMissingParentStages(finalStage))
      val jobSubmissionTime = clock.getTimeMillis()
      //并更新jobIdToActiveJob,activeJobs,resultOfJob数据结构
      //将jobId-->ActiveJob的对应关系添加到HashMap类型的数据结构jobIdToActiveJob中去  
      jobIdToActiveJob(jobId) = job   
      //将ActiveJob添加到HashSet类型的数据结构activeJobs中去  
      activeJobs += job//job对象存入HashMap
      //把ActiveJob赋值于ResultStage
      finalStage.resultOfJob = Some(job)
      //获取stageIds列表  
      //jobIdToStageIds存储的是jobId--stageIds的对应关系  
      //stageIds为HashSet[Int]类型的  
      //jobIdToStageIds在上面newResultStage过程中已被处理  
      val stageIds = jobIdToStageIds(jobId).toArray
      //返回最新StageInfo
      //stageIdToStage存储的是stageId-->Stage的对应关系    
      val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
      //向listenerBus发送SparkListernJobStart事件
      listenerBus.post(
        SparkListenerJobStart(job.jobId, jobSubmissionTime, stageInfos, properties))
      //提交finalStage
        submitStage(finalStage)
    }
    //提交等待中的Stage
    submitWaitingStages()
  }

  /** 
   *  在提交finalStage之前,如果存在没有提交父 Stage,则需要先提交没有提交提交父 Stage
   *  Submits stage, but first recursively(递归) submits any missing parents. 
   * */
  private def submitStage(stage: Stage) {
    //查找stage创建的jobId
    val jobId = activeJobForStage(stage)
    if (jobId.isDefined) {
      logDebug("submitStage(" + stage + ")")
      //如果当前stage不在等待中,并且 不在运行的状态,并且没有已经失败,就尝试提交它
      if (!waitingStages(stage) && !runningStages(stage) && !failedStages(stage)) {
        //获取父阶段Stage
        val missing = getMissingParentStages(stage).sortBy(_.id)
        logDebug("missing: " + missing)
        if (missing.isEmpty) {          
          logInfo("Submitting " + stage + " (" + stage.rdd + "), which has no missing parents")
          //如果所有的parent Stage都已经完成,那么提交该stage所包含的Task
          submitMissingTasks(stage, jobId.get)
        } else {
          //迭代该阶段所依赖的父调度阶段
          for (parent <- missing) {//如果存在则先提交该父阶段的Stage
            //有parent Stage未完成,则递归 提交它
            submitStage(parent)
          }
          //将子Stage放入waitingStages中等待
          waitingStages += stage
        }
      }
    } else {//无效的Stage,直接停止它
      abortStage(stage, "No active job for stage " + stage.id, None)
    }
  }

  /** 
   *  Called when stage's parents are available and we can now do its task. 
   *  用于提交还未计算的任务
   *  */
  
  private def submitMissingTasks(stage: Stage, jobId: Int) {
    logDebug("submitMissingTasks(" + stage + ")")
    // Get our pending tasks and remember them in our pendingTasks entry
    //pendingTasks存储待处理的Task,清空列表,由于当前Stage的任务刚开始提交,    
    stage.pendingTasks.clear()
    
    
    // First figure out the indexes of partition ids to compute.
    //首先计算出分区ID的索引计算
    val (allPartitions: Seq[Int], partitionsToCompute: Seq[Int]) = {
      stage match {
         
        case stage: ShuffleMapStage =>
          //如果Stage是Map任务,
          //把分区数转换成immutable.Range集合
          val allPartitions = 0 until stage.numPartitions      
           //outputLocs中partition对应的List为isEmpty,表示partition还未计算
          val filteredPartitions = allPartitions.filter { id => stage.outputLocs(id).isEmpty }
          (allPartitions, filteredPartitions)
        case stage: ResultStage =>
          //如果stage不是map任务,获取Stage的finalJob(ActiveJob),
          val job = stage.resultOfJob.get
          val allPartitions = 0 until job.numPartitions
          //并调用finished方法判断每个Partition的任务是否完成
          val filteredPartitions = allPartitions.filter { id => !job.finished(id) }
          (allPartitions, filteredPartitions)
      }
    }

    // Create internal accumulators if the stage has no accumulators initialized.
    //如果阶段没有初始化累加器,则创建内部累加器
    // Reset internal accumulators only if this stage is not partially submitted
    // Otherwise, we may override existing accumulator values from some tasks
    //仅当此阶段未部分提交时才复位内部累加器否则,我们可能会从某些任务中覆盖现有的累加器值
    //如果stage累加器为空,分区总数与待计算分区数相等,则重置内部累加器
    if (stage.internalAccumulators.isEmpty || allPartitions == partitionsToCompute) {
      stage.resetInternalAccumulators()
    }

    // Use the scheduling pool, job group, description, etc. from an ActiveJob associated
    // with this Stage
    //使用与此阶段关联的ActiveJob的调度池,作业组,说明等
    //获得Job(ActiveJob)属性文件
    val properties = jobIdToActiveJob(jobId).properties
   //将当前Stage加入运行中的Stage集合
    runningStages += stage
    // SparkListenerStageSubmitted should be posted before testing whether tasks are
    // serializable. If tasks are not serializable, a SparkListenerStageCompleted event
    // will be posted, which should always come after a corresponding SparkListenerStageSubmitted
    // event.
    //在测试任务是否可序列化之前,应该发布SparkListenerStageSubmitted, 如果任务不可序列化,
    // SparkListenerStageCompleted事件将被发布,该事件应该始终在相应的SparkListenerStageSubmitted事件之后。
    //SparkListenerStageSubmitted 提交之前测试任务是否序列化,如果可以序列化,则提交,否则
    outputCommitCoordinator.stageStart(stage.id)
    //task id任务和最佳位置列表
    val taskIdToLocations = try {
      stage match {
        case s: ShuffleMapStage =>
          //返回未计算partition的优先位置,TaskLocation
          partitionsToCompute.map { id => (id, getPreferredLocs(stage.rdd, id))}.toMap
        case s: ResultStage =>
          val job = s.resultOfJob.get
          partitionsToCompute.map { id =>
            val p = job.partitions(id)
            //返回的 未计算partition的优先位置,TaskLocation
            (id, getPreferredLocs(stage.rdd, p))
          }.toMap
      }
    } catch {
      case NonFatal(e) =>
        //创建新StageInfo,尝试ID自增1
        stage.makeNewStageAttempt(partitionsToCompute.size)
        //listenerBus发送SparkListenerStageSubmitted事件
        listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))
        abortStage(stage, s"Task creation failed: $e\n${e.getStackTraceString}", Some(e))
        runningStages -= stage
        return
    }
    //标记新的stage attempt  
    stage.makeNewStageAttempt(partitionsToCompute.size, taskIdToLocations.values.toSeq)
    //listenerBus发送SparkListenerStageSubmitted事件
    listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))

    // TODO: Maybe we can keep the taskBinary in Stage to avoid serializing it multiple times.
    // Broadcasted binary for the task, used to dispatch tasks to executors. Note that we broadcast
    // the serialized copy of the RDD and for each task we will deserialize it, which means each
    // task gets a different copy of the RDD. This provides stronger isolation between tasks that
    // might modify state of objects referenced in their closures. This is necessary in Hadoop
    // where the JobConf/Configuration object is not thread-safe.
    //用于任务的广播二进制，用于将任务分派给执行者,请注意，我们广播RDD的序列化副本，对于我们将反序列化的每个任务,这意味着每个任务获得RDD的不同副本。
    // 这样可以在可能修改其关闭引用的对象状态的任务之间提供更强的隔离, 这在Hadoop中是必需的,其中JobConf / Configuration对象不是线程安全的。
    //广播Task任务二进制,用于调度任务的执行,请注意,广播出的RDD序列化反序列化任务
    var taskBinary: Broadcast[Array[Byte]] = null
    try {
      // For ShuffleMapTask, serialize and broadcast (rdd, shuffleDep).
      // 对于ShuffleMapTask,序列化并广播,广播的是rdd和shuffleDep  
      // For ResultTask, serialize and broadcast (rdd, func).
      // 对于ResultTask,序列化并广播,广播的是rdd和func  
      val taskBinaryBytes: Array[Byte] = stage match {
        //序列化ShuffleMapStage  
        case stage: ShuffleMapStage =>
          //如果Stage是map任务,那么序列化Stage的RDD及ShuffleDependency
          closureSerializer.serialize((stage.rdd, stage.shuffleDep): AnyRef).array()
        case stage: ResultStage =>
          //如果不是map任务,那么序列化Stege及resultOfJob的处理函数
          closureSerializer.serialize((stage.rdd, stage.resultOfJob.get.func): AnyRef).array()
      }
      //通过sc广播序列化的task  
      taskBinary = sc.broadcast(taskBinaryBytes)
    } catch {
      // In the case of a failure during serialization, abort the stage.
      //如果序列化期间失败,则终止stage
      case e: NotSerializableException =>
        abortStage(stage, "Task not serializable: " + e.toString, Some(e))
        runningStages -= stage
        // Abort execution
        return
      case NonFatal(e) =>
        abortStage(stage, s"Task serialization failed: $e\n${e.getStackTraceString}", Some(e))
        runningStages -= stage
        return
    }
  //针对stage的每个分区构造task,形成tasks:ShuffleMapStage生成ShuffleMapTasks,ResultStage生成ResultTasks
    val tasks: Seq[Task[_]] = try {
      stage match {       
        case stage: ShuffleMapStage =>
          //如果Stage是ShuffleMapStage任务,则创建ShuffleMapTask
          partitionsToCompute.map { id =>
             //partitionsToCompute未计算的分区,找出未计算分区的最佳位置  
            val locs = taskIdToLocations(id)  
            //part 未计算的分区
            val part = stage.rdd.partitions(id)
            //创建ShuffleMapTask,其中包括任务执行的最佳位置,重试次数,分区,累加器
            new ShuffleMapTask(stage.id, stage.latestInfo.attemptId,
              taskBinary, part, locs, stage.internalAccumulators)
          }

        case stage: ResultStage => //创建ResultTask
          //获取Stage的finalJob(ActiveJob),
          val job = stage.resultOfJob.get
          //未计算的分区partitionsToCompute
          partitionsToCompute.map { id =>
            val p: Int = job.partitions(id)
            val part = stage.rdd.partitions(p)
            //找出未计算任务的最佳位置  
            val locs = taskIdToLocations(id)
             //其中包括任务执行的最佳位置,重试次数,分区,累加器
            new ResultTask(stage.id, stage.latestInfo.attemptId,
              taskBinary, part, locs, id, stage.internalAccumulators)
          }
      }
    } catch {
      case NonFatal(e) =>
        abortStage(stage, s"Task creation failed: $e\n${e.getStackTraceString}", Some(e))
        runningStages -= stage
        return
    }
    //如果存在tasks,则利用taskScheduler.submitTasks()提交task,否则标记stage已完成  
    if (tasks.size > 0) {
      logInfo("Submitting " + tasks.size + " missing tasks from " + stage + " (" + stage.rdd + ")")
     //将创建的所有Task都添加到stage.pendingTasks中,存储等待处理的Task
      stage.pendingTasks ++= tasks
      logDebug("New pending tasks: " + stage.pendingTasks)     
      //利用taskScheduler.submitTasks()提交task,
      taskScheduler.submitTasks(new TaskSet(
        tasks.toArray, stage.id, stage.latestInfo.attemptId, jobId, properties))
      //更新StageInfo的任务提交时间  
      stage.latestInfo.submissionTime = Some(clock.getTimeMillis())
    } else {
      // Because we posted SparkListenerStageSubmitted earlier, we should mark
      // the stage as completed here in case there are no tasks to run
      //因为我们在之前发布了SparkListenerStageSubmission，所以我们应该将这个阶段标记为完成,否则没有任务可以运行
      //标记stage已完成  
      markStageAsFinished(stage, None)

      val debugString = stage match {
        case stage: ShuffleMapStage =>
          s"Stage ${stage} is actually done; " +
            s"(available: ${stage.isAvailable}," +
            s"available outputs: ${stage.numAvailableOutputs}," +
            s"partitions: ${stage.numPartitions})"
        case stage : ResultStage =>
          s"Stage ${stage} is actually done; (partitions: ${stage.numPartitions})"
      }
      logDebug(debugString)
    }
  }

  /** 
   *  Merge updates from a task to our local accumulator values 
   *  合并更新一个任务到本地的累加值
   *  */
  private def updateAccumulators(event: CompletionEvent): Unit = {
    val task = event.task
    val stage = stageIdToStage(task.stageId)
    if (event.accumUpdates != null) {
      try {
        Accumulators.add(event.accumUpdates)

        event.accumUpdates.foreach { case (id, partialValue) =>
          // In this instance, although the reference in Accumulators.originals is a WeakRef,
          //在这种情况下,虽然在Accumulators.originals中的引用是WeakRef，
          // it's guaranteed to exist since the event.accumUpdates Map exists
          //它保证存在,因为event.accumUpdates Map存在
          val acc = Accumulators.originals(id).get match {
            case Some(accum) => accum.asInstanceOf[Accumulable[Any, Any]]
            case None => throw new NullPointerException("Non-existent reference to Accumulator")
          }

          // To avoid UI cruft, ignore cases where value wasn't updated
          //为了避免UI cruft,忽略值未更新的情况
          if (acc.name.isDefined && partialValue != acc.zero) {
            val name = acc.name.get
            val value = s"${acc.value}"
            stage.latestInfo.accumulables(id) =
              new AccumulableInfo(id, name, None, value, acc.isInternal)
            event.taskInfo.accumulables +=
              new AccumulableInfo(id, name, Some(s"$partialValue"), value, acc.isInternal)
          }
        }
      } catch {
        // If we see an exception during accumulator update, just log the
        // error and move on.
        //如果在累加器更新期间看到异常,只需记录错误并继续
        case e: Exception =>
          logError(s"Failed to update accumulators for $task", e)
      }
    }
  }

  /**
   * Responds to a task finishing. This is called inside the event loop so it assumes that it can
   * modify the scheduler's internal state. Use taskEnded() to post a task end event from outside.
    *
    * 回应任务整理,这在事件循环中被调用,因此它假定它可以修改调度程序的内部状态,使用taskEnded（）从外部发布任务结束事件,
   * 负责处理获取到的计算结果
   */
  private[scheduler] def handleTaskCompletion(event: CompletionEvent) {
    val task = event.task
    val stageId = task.stageId
    val taskType = Utils.getFormattedClassName(task)

    outputCommitCoordinator.taskCompleted(
      stageId,
      task.partitionId,
      event.taskInfo.attemptNumber, // this is a task attempt number,Taks任务重试次数
      event.reason)

    // The success case is dealt with separately below, since we need to compute accumulator
    // updates before posting.
    //下面单独处理成功案例,因为我们需要在发布之前计算累加器更新
    //当任务不成功
    if (event.reason != Success) {
      val attemptId = task.stageAttemptId
      //发送事件结束任务
      listenerBus.post(SparkListenerTaskEnd(stageId, attemptId, taskType, event.reason,
        event.taskInfo, event.taskMetrics))
    }

    if (!stageIdToStage.contains(task.stageId)) {
      // Skip all the actions if the stage has been cancelled.
      //如果stage已被取消,请跳过所有动作。
      return
    }

    val stage = stageIdToStage(task.stageId)
    event.reason match {
      case Success =>
        //向listenerBus发送SparkListenerTaskEnd
        listenerBus.post(SparkListenerTaskEnd(stageId, stage.latestInfo.attemptId, taskType,
          event.reason, event.taskInfo, event.taskMetrics))
        stage.pendingTasks -= task //从存储等待处理的Task的列表删除此任务
        task match {
          case rt: ResultTask[_, _] =>           
            // Cast to ResultStage here because it's part of the ResultTask
            //由于它属于ResultTask的一部分,因此在此处转换为ResultStage
            // TODO Refactor this out to a function that accepts a ResultStage
            val resultStage = stage.asInstanceOf[ResultStage]
            resultStage.resultOfJob match {
              case Some(job) => //ActiveJob
                if (!job.finished(rt.outputId)) {
                  //1)标识ActiveJob的finished里对应分区的任务完成状态,并且将已完成的任务数numFinished加1
                  updateAccumulators(event)
                  job.finished(rt.outputId) = true//标识对应分区的任务为完成状态
                  job.numFinished += 1//将已完成的任务数加1
                  // If the whole job has finished, remove it
                  //2)如果ActiveJob的所有任务都完成,则标记当前Stage完成并SparkListenerJobEnd发送事件
                  if (job.numFinished == job.numPartitions) {
                    markStageAsFinished(resultStage)//标记stage完成
                    cleanupStateForJobAndIndependentStages(job)   
                    //SparkListenerJobEnd发送事件
                    listenerBus.post(
                      SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobSucceeded))
                  }

                  // taskSucceeded runs some user code that might throw an exception. Make sure
                  // we are resilient against that.
                  //对于taskSucceeded,会运行用户自定义的结果处理函数,因此可能会抛出异常
                  try {
                    //3)调用JobWaiter的taskSucceeded方法,以便通知JobWaiter有任务成功
                    job.listener.taskSucceeded(rt.outputId, event.result)
                  } catch {
                    case e: Exception =>
                      // TODO: Perhaps we want to mark the resultStage as failed?
                      //调用JobWaiter的taskSucceeded方法,以便通知JobWaiter有任务失败
                      job.listener.jobFailed(new SparkDriverExecutionException(e))
                  }
                }
              case None =>
                //应该有任务的推测执行,因此一个Task可能会运行多次
                logInfo("Ignoring result from " + rt + " because its job has finished")
            }

          case smt: ShuffleMapTask =>
            //将Stage强制转换ShuffleMapStage
            val shuffleStage = stage.asInstanceOf[ShuffleMapStage]
            //更新累加器
            updateAccumulators(event)
            //将CompletionEvent事件result强制转换MapStatus
            val status = event.result.asInstanceOf[MapStatus]
            //获得executorId
            val execId = status.location.executorId
            logDebug("ShuffleMapTask finished on " + execId)
            //判断是否有失败execId,并且ShuffleMapTask的epoch值小于等于HashMap存储execId的值
            if (failedEpoch.contains(execId) && smt.epoch <= failedEpoch(execId)) {
              //忽略可能是来自executor伪造ShuffleMapTask完成
              logInfo(s"Ignoring possibly bogus $smt completion from executor $execId")
            } else {              
              //将Task的partitionId和MapStatus追加到Stage的outputLocs中
              shuffleStage.addOutputLoc(smt.partitionId, status)
            }
            //判断运行stage列表包含当前shuffleStage,及shuffleStage待处理的任务列表为空
            if (runningStages.contains(shuffleStage) && shuffleStage.pendingTasks.isEmpty) {
              //将当前Stage标记为完成
              markStageAsFinished(shuffleStage)
              logInfo("looking for newly runnable stages")
              logInfo("running: " + runningStages)
              logInfo("waiting: " + waitingStages)
              logInfo("failed: " + failedStages)

              // We supply true to increment the epoch number here in case this is a
              // recomputation of the map outputs. In that case, some nodes may have cached
              // locations with holes (from when we detected the error) and will need the
              // epoch incremented to refetch them.
              // TODO: Only increment the epoch number if this is not the first time
              //       we registered these map outputs.         
              //将前当Stage的ShuffleId和OutputLocs中的MapStatus注册到MapOutTracker,这里注册的Map任务状态最终被
              //reduce任务所用
              mapOutputTracker.registerMapOutputs(
                shuffleStage.shuffleDep.shuffleId,//将前当Stage的ShuffleId
                shuffleStage.outputLocs.map(list => if (list.isEmpty) null else list.head),//OutputLocs中的MapStatus
                changeEpoch = true)
              clearCacheLocs()
              //如果Stage的OutputLocs中存储的分区包含Nil,说明有任务失败,需再次提交此Stage
              if (shuffleStage.outputLocs.contains(Nil)) {
                // Some tasks had failed; let's resubmit this shuffleStage
                // TODO: Lower-level scheduler should also deal with this
                logInfo("Resubmitting " + shuffleStage + " (" + shuffleStage.name +
                  ") because some of its tasks had failed: " +
                  shuffleStage.outputLocs.zipWithIndex.filter(_._1.isEmpty)
                      .map(_._2).mkString(", "))
                submitStage(shuffleStage)//再次提交此Stage
              } else {
                //如果Stage的OutputLocs中存储的分区不包含Nil,说明所有任务执行成功执行
                val newlyRunnable = new ArrayBuffer[Stage]
                for (shuffleStage <- waitingStages) {//重新提交这个Stage,重新计算丢失部分
                  logInfo("Missing parents for " + shuffleStage + ": " +
                    //部分Stage失败重新提交
                    getMissingParentStages(shuffleStage))
                }
                //遍历WaitingStage中的Stage并将它们放入runningStage
                for (shuffleStage <- waitingStages if getMissingParentStages(shuffleStage).isEmpty)
                {
                  newlyRunnable += shuffleStage//可以提交Stage
                }
                //删除等待运行Stage
                waitingStages --= newlyRunnable
                //增加正在运行Stage
                runningStages ++= newlyRunnable
                for {                  
                  shuffleStage <- newlyRunnable.sortBy(_.id)
                  jobId <- activeJobForStage(shuffleStage)
                } {
                  logInfo("Submitting " + shuffleStage + " (" +
                    shuffleStage.rdd + "), which is now runnable")
                    //逐个提交Stage运行Task任务
                  submitMissingTasks(shuffleStage, jobId)
                }
              }
            }
          }

      case Resubmitted =>
        logInfo("Resubmitted " + task + ", so marking it as still running")
        stage.pendingTasks += task //重新提交task

      case FetchFailed(bmAddress, shuffleId, mapId, reduceId, failureMessage) =>
        val failedStage = stageIdToStage(task.stageId)
        val mapStage = shuffleToMapStage(shuffleId)

        if (failedStage.latestInfo.attemptId != task.stageAttemptId) {
          logInfo(s"Ignoring fetch failure from $task as it's from $failedStage attempt" +
            s" ${task.stageAttemptId} and there is a more recent attempt for that stage " +
            s"(attempt ID ${failedStage.latestInfo.attemptId}) running")
        } else {

          // It is likely that we receive multiple FetchFailed for a single stage (because we have
          // multiple tasks running concurrently on different executors). In that case, it is
          // possible the fetch failure has already been handled by the scheduler.
          //我们可能会在单个阶段收到多个FetchFailed(因为我们有多个任务在不同的执行程序上同时运行),在这种情况下,调度器已经可以处理提取失败
          if (runningStages.contains(failedStage)) {
            logInfo(s"Marking $failedStage (${failedStage.name}) as failed " +
              s"due to a fetch failure from $mapStage (${mapStage.name})")
            markStageAsFinished(failedStage, Some(failureMessage))
          } else {
            logDebug(s"Received fetch failure from $task, but its from $failedStage which is no " +
              s"longer running")
          }

          if (disallowStageRetryForTest) {
            abortStage(failedStage, "Fetch failure will not retry stage due to testing config",
              None)
          } else if (failedStages.isEmpty) {
            // Don't schedule an event to resubmit failed stages if failed isn't empty, because
            // in that case the event will already have been scheduled.
            //如果失败不为空,则不要安排事件重新提交失败的阶段,因为在这种情况下事件已经被安排了
            // TODO: Cancel running tasks in the stage
            logInfo(s"Resubmitting $mapStage (${mapStage.name}) and " +
              s"$failedStage (${failedStage.name}) due to fetch failure")
            messageScheduler.schedule(new Runnable {
              override def run(): Unit = eventProcessLoop.post(ResubmitFailedStages)
            }, DAGScheduler.RESUBMIT_TIMEOUT, TimeUnit.MILLISECONDS)
          }
          failedStages += failedStage
          failedStages += mapStage
          // Mark the map whose fetch failed as broken in the map stage
          //在Map阶段标记其抓取失败的地图已损坏
          if (mapId != -1) {
            mapStage.removeOutputLoc(mapId, bmAddress)
            mapOutputTracker.unregisterMapOutput(shuffleId, mapId, bmAddress)
          }

          // TODO: mark the executor as failed only if there were lots of fetch failures on it
          if (bmAddress != null) {
            handleExecutorLost(bmAddress.executorId, fetchFailed = true, Some(task.epoch))
          }
        }

      case commitDenied: TaskCommitDenied =>
        // Do nothing here, left up to the TaskScheduler to decide how to handle denied commits
        //在这里什么都不做,留给TaskScheduler来决定如何处理被拒绝的提交

      case exceptionFailure: ExceptionFailure =>
        // Do nothing here, left up to the TaskScheduler to decide how to handle user failures
        //在这里什么都不做,留给TaskScheduler来决定如何处理用户失败

      case TaskResultLost =>
        // Do nothing here; the TaskScheduler handles these failures and resubmits the task.
        //这里什么都不做TaskScheduler处理这些故障并重新提交任务。

      case other =>
        // Unrecognized failure - also do nothing. If the task fails repeatedly, the TaskScheduler
        // will abort the job.
        //无法识别的故障 - 也不做任何事情,如果任务重复失败,TaskScheduler将中止作业。
    }
    submitWaitingStages()
  }

  /**
   * Responds to an executor being lost,This is called inside the event loop, so it assumes it can
   * modify the scheduler's internal state. Use executorLost() to post a loss event from outside.
    *
   * 响应执行器丢失,这是在事件循环内调用的,所以它假定它可以修改调度程序的内部状态,使用executorLost（）从外部发布丢失事件。
    *
   * We will also assume that we've lost all shuffle blocks associated with the executor if the
   * executor serves its own blocks (i.e., we're not using external shuffle) OR a FetchFailed
   * occurred, in which case we presume all shuffle data related to this executor to be lost.
    *
    * 我们还将假设我们已经失去了与执行者相关联的所有shuffle执行者服务自己的块（即我们没有使用外部shuffle
    * ）或发生FetchFailed,在这种情况下,我们假定所有与此执行者相关的shuffle数据丢失

   * Optionally the epoch during which the failure was caught can be passed to avoid allowing
   * stray fetch failures from possibly retriggering the detection of a node as lost.
    *
    * 可选地,可以传递捕获故障的时期,以避免允许流离失踪从可能重新触发节点的检测丢失。
   * 
   */
  private[scheduler] def handleExecutorLost(
      execId: String,
      fetchFailed: Boolean,
      maybeEpoch: Option[Long] = None) {
    val currentEpoch = maybeEpoch.getOrElse(mapOutputTracker.getEpoch)
    if (!failedEpoch.contains(execId) || failedEpoch(execId) < currentEpoch) {
      failedEpoch(execId) = currentEpoch
      logInfo("Executor lost: %s (epoch %d)".format(execId, currentEpoch))
      blockManagerMaster.removeExecutor(execId)

      if (!env.blockManager.externalShuffleServiceEnabled || fetchFailed) {
        // TODO: This will be really slow if we keep accumulating shuffle map stages
        for ((shuffleId, stage) <- shuffleToMapStage) {
          stage.removeOutputsOnExecutor(execId)
          val locs = stage.outputLocs.map(list => if (list.isEmpty) null else list.head)
          mapOutputTracker.registerMapOutputs(shuffleId, locs, changeEpoch = true)
        }
        if (shuffleToMapStage.isEmpty) {
          mapOutputTracker.incrementEpoch()
        }
        clearCacheLocs()
      }
    } else {
      logDebug("Additional executor lost message for " + execId +
               "(epoch " + currentEpoch + ")")
    }
    submitWaitingStages()
  }

  private[scheduler] def handleExecutorAdded(execId: String, host: String) {
    // remove from failedEpoch(execId) ?
    if (failedEpoch.contains(execId)) {
      logInfo("Host added was in lost list earlier: " + host)
      //host从failedEpoch中移除
      failedEpoch -= execId
    }
    //用于将跟踪失败的节点重新恢复正常和提交等待中的Stage
    submitWaitingStages()
  }

  private[scheduler] def handleStageCancellation(stageId: Int) {
    stageIdToStage.get(stageId) match {
      case Some(stage) =>
        val jobsThatUseStage: Array[Int] = stage.jobIds.toArray
        jobsThatUseStage.foreach { jobId =>
          handleJobCancellation(jobId, s"because Stage $stageId was cancelled")
        }
      case None =>
        logInfo("No active jobs to kill for Stage " + stageId)
    }
    submitWaitingStages()
  }

  private[scheduler] def handleJobCancellation(jobId: Int, reason: String = "") {
    if (!jobIdToStageIds.contains(jobId)) {
      logDebug("Trying to cancel unregistered job " + jobId)
    } else {
      failJobAndIndependentStages(
        jobIdToActiveJob(jobId), "Job %d cancelled %s".format(jobId, reason))
    }
    submitWaitingStages()
  }

  /**
   * Marks a stage as finished and removes it from the list of running stages.
   * 标志一个阶段完成,并将其从运行阶段的列表中删除
   */
  private def markStageAsFinished(stage: Stage, errorMessage: Option[String] = None): Unit = {
    val serviceTime = stage.latestInfo.submissionTime match {
      case Some(t) => "%.03f".format((clock.getTimeMillis() - t) / 1000.0)//设置最新提交时间格式化为秒
      case _ => "Unknown"
    }
    if (errorMessage.isEmpty) {
      logInfo("%s (%s) finished in %s s".format(stage, stage.name, serviceTime))
      //设置StageInfo完成时间
      stage.latestInfo.completionTime = Some(clock.getTimeMillis())
    } else {
      stage.latestInfo.stageFailed(errorMessage.get)
      logInfo("%s (%s) failed in %s s".format(stage, stage.name, serviceTime))
    }
    //stage完成,移除stage
    outputCommitCoordinator.stageEnd(stage.id)
    //发送SparkListenerStageCompleted完成事件
    listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
    //删除待运行Stage
    runningStages -= stage
  }

  /**
   * Aborts all jobs depending on a particular Stage. This is called in response to a task set
   * being canceled by the TaskScheduler. Use taskSetFailed() to inject this event from outside.
    *
    * 根据特定阶段中止所有工作,这是为了响应TaskScheduler取消的任务集而调用的,使用taskSetFailed()从外部注入此事件。
   */
  private[scheduler] def abortStage(
      failedStage: Stage,
      reason: String,
      exception: Option[Throwable]): Unit = {
    if (!stageIdToStage.contains(failedStage.id)) {
      // Skip all the actions if the stage has been removed.
      //如果stage已被删除，请跳过所有动作。
      return
    }
    val dependentJobs: Seq[ActiveJob] =
      activeJobs.filter(job => stageDependsOn(job.finalStage, failedStage)).toSeq
    failedStage.latestInfo.completionTime = Some(clock.getTimeMillis())
    for (job <- dependentJobs) {
      failJobAndIndependentStages(job, s"Job aborted due to stage failure: $reason", exception)
    }
    if (dependentJobs.isEmpty) {
      logInfo("Ignoring failure of " + failedStage + " because all jobs depending on it are done")
    }
  }

  /** 
   *  Fails a job and all stages that are only used by that job,and cleans up relevant state. 
   *  失败一个工作Job和所有阶段(stage)只使用的工作,并清理相关状态
   *  */
  private def failJobAndIndependentStages(
      job: ActiveJob,
      failureReason: String,
      exception: Option[Throwable] = None): Unit = {
    val error = new SparkException(failureReason, exception.getOrElse(null))
    var ableToCancelStages = true

    val shouldInterruptThread =
      if (job.properties == null) false
      else job.properties.getProperty(SparkContext.SPARK_JOB_INTERRUPT_ON_CANCEL, "false").toBoolean

    // Cancel all independent, running stages.
    //取消所有独立的运行阶段
    val stages = jobIdToStageIds(job.jobId)
    if (stages.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    }
    stages.foreach { stageId =>
      val jobsForStage: Option[HashSet[Int]] = stageIdToStage.get(stageId).map(_.jobIds)
      if (jobsForStage.isEmpty || !jobsForStage.get.contains(job.jobId)) {
        logError(
          "Job %d not registered for stage %d even though that stage was registered for the job"
            .format(job.jobId, stageId))
      } else if (jobsForStage.get.size == 1) {
        if (!stageIdToStage.contains(stageId)) {
          logError(s"Missing Stage for stage with id $stageId")
        } else {
          // This is the only job that uses this stage, so fail the stage if it is running.
          //这是唯一一个使用这个阶段的工作,所以,如果它正在运行失败的阶段
          val stage = stageIdToStage(stageId)
          if (runningStages.contains(stage)) {
            try {
              // cancelTasks will fail if a SchedulerBackend does not implement killTask
              //如果SchedulerBackend不实现killTask,则cancelTask将失败
              taskScheduler.cancelTasks(stageId, shouldInterruptThread)
              markStageAsFinished(stage, Some(failureReason))
            } catch {
              case e: UnsupportedOperationException =>
                logInfo(s"Could not cancel tasks for stage $stageId", e)
              ableToCancelStages = false
            }
          }
        }
      }
    }

    if (ableToCancelStages) {
      job.listener.jobFailed(error)
      cleanupStateForJobAndIndependentStages(job)
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  /** 
   *  Return true if one of stage's ancestors is target. 
   *  如果一个Stage是目标父Stage,则返回true
   *  */
  private def stageDependsOn(stage: Stage, target: Stage): Boolean = {
    if (stage == target) {
      return true
    }
    val visitedRdds = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    //我们在这里手动维护堆栈,以防止由递归访问引起的StackOverflowError
    val waitingForVisit = new Stack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visitedRdds(rdd)) {
        visitedRdds += rdd
        for (dep <- rdd.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              val mapStage = getShuffleMapStage(shufDep, stage.firstJobId)
              if (!mapStage.isAvailable) {
                waitingForVisit.push(mapStage.rdd)
              }  // Otherwise there's no need to follow the dependency back
              //否则不需要依赖依赖
            case narrowDep: NarrowDependency[_] =>
              waitingForVisit.push(narrowDep.rdd)
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
    visitedRdds.contains(target.rdd)
  }

  /**
   * Gets the locality information associated with a partition of a particular RDD.
    *
    * 获取与特定RDD的分区相关联的位置信息,就是这个 partition 对应的 task 的优先位置
   * This method is thread-safe and is called from both DAGScheduler and SparkContext.
    * 此方法是线程安全的,并且从DAGScheduler和SparkContext调用
   *
   * @param rdd whose partitions are to be looked at 其分区将被看到
   * @param partition to lookup locality information for//分区查找本地信息
   * @return list of machines that are preferred by the partition//由分区优选的机器列表
   */
  private[spark]
  def getPreferredLocs(rdd: RDD[_], partition: Int): Seq[TaskLocation] = {
    getPreferredLocsInternal(rdd, partition, new HashSet)
  }

  /**
   * Recursive implementation for getPreferredLocs.
   * 首先查询DAGScheduler的内存数据结构中是否存在当前Paritition的数据本地性的信息,如果有的话直接返回,
   * 如果没有首先会调用rdd.getPreferedLocations
   * This method is thread-safe because it only accesses DAGScheduler state through thread-safe
   * methods (getCacheLocs()); please be careful when modifying this method, because any new
   * DAGScheduler state accessed by it may require additional synchronization.
    *
    * 这种方法是线程安全的,因为它只通过线程安全方法(getCacheLocs())访问DAGScheduler状态,
    * 修改此方法时请小心,因为其访问的任何新的DAGScheduler状态可能需要额外的同步。
   */
  private def getPreferredLocsInternal(
      rdd: RDD[_],
      partition: Int,
      visited: HashSet[(RDD[_], Int)]): Seq[TaskLocation] = {
    // If the partition has already been visited, no need to re-visit.
    //如果分区已经被访问，则无需重新访问
    // This avoids exponential path exploration.  SPARK-695
    //这避免了指数级的路径探索,SPARK-695
    if (!visited.add((rdd, partition))) {
      // Nil has already been returned for previously visited partitions.
      //以前访问过的分区已经返回了Nil
      return Nil
    }
    // If the partition is cached, return the cache locations
    //如果分区被缓存,返回缓存位置
    val cached = getCacheLocs(rdd)(partition)
    //nonEmpty非空
    if (cached.nonEmpty) {
      return cached
    }
    // If the RDD has some placement preferences (as is the case for input RDDs), get those
    //如果RDD有一些放置首选项(如输入RDD的情况),得到那些
    val rddPrefs = rdd.preferredLocations(rdd.partitions(partition)).toList
    if (rddPrefs.nonEmpty) {
      return rddPrefs.map(TaskLocation(_))
    }

    // If the RDD has narrow dependencies, pick the first partition of the first narrow dependency
    // that has any placement preferences. Ideally we would choose based on transfer sizes,
    // but this will do for now.
    //如果RDD具有较窄的依赖关系,则选择具有任何放置首选项的第一个窄依赖关系的第一个分区,理想情况下,我们会根据转移大小进行选择,但现在将会进行。
    rdd.dependencies.foreach {
      case n: NarrowDependency[_] =>
        for (inPart <- n.getParents(partition)) {
          val locs = getPreferredLocsInternal(n.rdd, inPart, visited)
          if (locs != Nil) {
            return locs
          }
        }
      case _ =>
    }

    // If the RDD has shuffle dependencies and shuffle locality is enabled, pick locations that
    // have at least REDUCER_PREF_LOCS_FRACTION of data as preferred locations
    //如果有依赖关系和洗牌洗牌RDD局部启用,则选择该位置至少有REDUCER_PREF_LOCS_FRACTION的数据作为首选位置
    if (shuffleLocalityEnabled && rdd.partitions.length < SHUFFLE_PREF_REDUCE_THRESHOLD) {
      rdd.dependencies.foreach {
        case s: ShuffleDependency[_, _, _] =>
          if (s.rdd.partitions.length < SHUFFLE_PREF_MAP_THRESHOLD) {
            // Get the preferred map output locations for this reducer
            //获取此reducer的首选映射输出位置
            val topLocsForReducer = mapOutputTracker.getLocationsWithLargestOutputs(s.shuffleId,
              partition, rdd.partitions.length, REDUCER_PREF_LOCS_FRACTION)
            if (topLocsForReducer.nonEmpty) {
              return topLocsForReducer.get.map(loc => TaskLocation(loc.host, loc.executorId))
            }
          }
        case _ =>
      }
    }
    Nil
  }

  def stop() {
    logInfo("Stopping DAGScheduler")
    messageScheduler.shutdownNow()
    eventProcessLoop.stop()
    taskScheduler.stop()
  }

  // Start the event thread and register the metrics source at the end of the constructor
  //启动事件线程并在构造函数的末尾注册指标源
  env.metricsSystem.registerSource(metricsSource)
  eventProcessLoop.start()
}
/**
 * 主要职责是调用DAGScheduler相应的方法来处理DAGScheduler发送给它的各种消息
 */
private[scheduler] class DAGSchedulerEventProcessLoop(dagScheduler: DAGScheduler)
  extends EventLoop[DAGSchedulerEvent]("dag-scheduler-event-loop") with Logging {

  private[this] val timer = dagScheduler.metricsSource.messageProcessingTimer

  /**
   * The main event loop of the DAG scheduler.
   * DAGScheduler中事件主循环 
   */
  override def onReceive(event: DAGSchedulerEvent): Unit = {
    val timerContext = timer.time()
    try {
      //调用doOnReceive()方法,将DAGSchedulerEvent类型的event传递进去  
      doOnReceive(event)
    } finally {
      timerContext.stop()
    }
  }
  //事件处理调度函数,模式匹配。
  private def doOnReceive(event: DAGSchedulerEvent): Unit = event match {
    //接收提交job,来自与RDD->SparkContext->DAGScheduler.submit方法。需要在这里中转一下,是为了模块功能的一致性。
    case JobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties) =>
      dagScheduler.handleJobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties)
    //消息源org.apache.spark.ui.jobs.JobProgressTab,在GUI上显示一个SparkContext的Job的执行状态。    
    //用户可以cancel一个Stage,会通过SparkContext->DAGScheduler 传递到这里。      
    case StageCancelled(stageId) =>
      dagScheduler.handleStageCancellation(stageId)
    //来自于org.apache.spark.scheduler.JobWaiter的消息。取消一个Job
    case JobCancelled(jobId) =>
      dagScheduler.handleJobCancellation(jobId)

    case JobGroupCancelled(groupId) => // 取消整个Job Group
      dagScheduler.handleJobGroupCancelled(groupId)
    case AllJobsCancelled => //取消所有Job
      dagScheduler.doCancelAllJobs()
    case ExecutorAdded(execId, host) =>
     // TaskScheduler得到一个Executor被添加的消息。具体来自org.apache.spark.scheduler.TaskSchedulerImpl.resourceOffers
      dagScheduler.handleExecutorAdded(execId, host)
  
    case ExecutorLost(execId) => //来自TaskScheduler
      dagScheduler.handleExecutorLost(execId, fetchFailed = false)

    case BeginEvent(task, taskInfo) => //来自TaskScheduler
      dagScheduler.handleBeginEvent(task, taskInfo)

    case GettingResultEvent(taskInfo) => //处理获得TaskResult信息的消息
      dagScheduler.handleGetTaskResult(taskInfo)

    case completion @ CompletionEvent(task, reason, _, _, taskInfo, taskMetrics) =>
      //来自TaskScheduler,报告task是完成或者失败
      dagScheduler.handleTaskCompletion(completion)

    case TaskSetFailed(taskSet, reason, exception) =>
      //来自TaskScheduler,要么TaskSet失败次数超过阈值或者由于Job Cancel
      dagScheduler.handleTaskSetFailed(taskSet, reason, exception)

    case ResubmitFailedStages =>
      //当一个Stage处理失败时,重试,来自org.apache.spark.scheduler.DAGScheduler.handleTaskCompletion
      dagScheduler.resubmitFailedStages()
  }

  override def onError(e: Throwable): Unit = {
    logError("DAGSchedulerEventProcessLoop failed; shutting down SparkContext", e)
    try {
      dagScheduler.doCancelAllJobs()
    } catch {
      case t: Throwable => logError("DAGScheduler failed to cancel all jobs.", t)
    }
    dagScheduler.sc.stop()
  }

  override def onStop(): Unit = {
    // Cancel any active jobs in postStop hook
    //取消postStop挂钩中的任何活动作业
    dagScheduler.cleanUpAfterSchedulerStop()
  }
}

private[spark] object DAGScheduler {
  // The time, in millis, to wait for fetch failure events to stop coming in after one is detected;
  // this is a simplistic way to avoid resubmitting tasks in the non-fetchable map stage one by one
  // as more failure events come in
  //以毫秒为单位等待获取失败事件停止进入的时间;
  //这是一个简单的方法,以避免在不可抓取的地图阶段重新提交任务,因为更多的故障事件进入
  val RESUBMIT_TIMEOUT = 200
}
