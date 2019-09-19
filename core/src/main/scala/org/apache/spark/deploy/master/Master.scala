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

import java.io.FileNotFoundException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.{ScheduledFuture, TimeUnit}

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.language.postfixOps
import scala.util.Random

import org.apache.hadoop.fs.Path

import org.apache.spark.rpc._
import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.{ApplicationDescription, DriverDescription,
  ExecutorState, SparkHadoopUtil}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.history.HistoryServer
import org.apache.spark.deploy.master.DriverState.DriverState
import org.apache.spark.deploy.master.MasterMessages._
import org.apache.spark.deploy.master.ui.MasterWebUI
import org.apache.spark.deploy.rest.StandaloneRestServer
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.scheduler.{EventLoggingListener, ReplayListenerBus}
import org.apache.spark.serializer.{JavaSerializer, Serializer}
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.{ThreadUtils, SignalLogger, Utils}

private[deploy] class Master(
    override val rpcEnv: RpcEnv,
    address: RpcAddress,
    webUiPort: Int,
    val securityMgr: SecurityManager,
    val conf: SparkConf)
  extends ThreadSafeRpcEndpoint with Logging with LeaderElectable {

  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")////创建大小为1的固定线程池

  private val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)

  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss") //精确到秒 For application IDs 
  //如果master没有收到worker的心跳,那么将在这么多秒之后,master将丢弃该worker（默认60秒（60000毫秒）
  private val WORKER_TIMEOUT_MS = conf.getLong("spark.worker.timeout", 60) * 1000
  //web UI上最多展示几个已结束应用。更早的应用的数将被删除
  private val RETAINED_APPLICATIONS = conf.getInt("spark.deploy.retainedApplications", 200)
  //web UI上最多展示几个已结束的驱动器。更早的驱动器进程数据将被删除
  private val RETAINED_DRIVERS = conf.getInt("spark.deploy.retainedDrivers", 200)
  //worker判断死亡时间
  private val REAPER_ITERATIONS = conf.getInt("spark.dead.worker.persistence", 15)
  //设置启用master备用恢复模式,默认为NONE
  private val RECOVERY_MODE = conf.get("spark.deploy.recoveryMode", "NONE")
  //一个HashSet用于保存WorkerInfo
  val workers = new HashSet[WorkerInfo]
  //一个HashMap Appid-> ApplicationInfo
  val idToApp = new HashMap[String, ApplicationInfo]
  //等待schedule调度App  
  val waitingApps = new ArrayBuffer[ApplicationInfo]
  //一个HashSet用于保存客户端（SparkSubmit）提交的任务
  val apps = new HashSet[ApplicationInfo]
  //一个HashMap用保存workid -> WorkerInfo
  private val idToWorker = new HashMap[String, WorkerInfo]
  //注册Worker的addess与WorkerInfo的映射关系
  private val addressToWorker = new HashMap[RpcAddress, WorkerInfo]
//HashMap用保存RpcEndpointRef -> ApplicationInfo 
  private val endpointToApp = new HashMap[RpcEndpointRef, ApplicationInfo]
  private val addressToApp = new HashMap[RpcAddress, ApplicationInfo]
  private val completedApps = new ArrayBuffer[ApplicationInfo]
  private var nextAppNumber = 0
  private val appIdToUI = new HashMap[String, SparkUI]
  //保存DriverInfo
  private val drivers = new HashSet[DriverInfo]
  //完成Driver
  private val completedDrivers = new ArrayBuffer[DriverInfo]
  // Drivers currently spooled for scheduling
  //等待被高度的Application
  private val waitingDrivers = new ArrayBuffer[DriverInfo]
  private var nextDriverNumber = 0

  Utils.checkHost(address.host, "Expected hostname")

  private val masterMetricsSystem = MetricsSystem.createMetricsSystem("master", conf, securityMgr)
  private val applicationMetricsSystem = MetricsSystem.createMetricsSystem("applications", conf,
    securityMgr)
  private val masterSource = new MasterSource(this)

  // After onStart, webUi will be set
  //在onStart之后,将会设置webUi
  private var webUi: MasterWebUI = null

  private val masterPublicAddress = {
  //Spark master和workers使用的公共DNS（默认空）
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else address.host
  }

  private val masterUrl = address.toSparkURL
  private var masterWebUiUrl: String = _
 //
  private var state = RecoveryState.STANDBY

  private var persistenceEngine: PersistenceEngine = _

  private var leaderElectionAgent: LeaderElectionAgent = _

  private var recoveryCompletionTask: ScheduledFuture[_] = _

  private var checkForWorkerTimeOutTask: ScheduledFuture[_] = _

  // As a temporary workaround before better ways of configuring memory, we allow users to set
  // a flag that will perform round-robin scheduling across the nodes (spreading out each app
  // among all the nodes) instead of trying to consolidate each app onto a small # of nodes.
  //Executor的分配方式有两种,一种是倾向于把任务分散在多个节点上,一种是在尽量少的节点上运行,
  //由参数spark.deploy.spreadOut参数来决定的,默认是true,把任务分散到多个节点上
  private val spreadOutApps = conf.getBoolean("spark.deploy.spreadOut", true)

  // Default maxCores for applications that don't specify it (i.e. pass Int.MaxValue)
  //如果没有设置spark.cores.max,该参数设置Standalone集群分配给应用程序的最大内核数,
  //如果不设置,应用程序获取所有的有效内核。
  private val defaultCores = conf.getInt("spark.deploy.defaultCores", Int.MaxValue)
  if (defaultCores < 1) {
    throw new SparkException("spark.deploy.defaultCores must be positive")
  }
  // Alternative application submission gateway that is stable across Spark versions
  //替代应用程序提交rest是稳定的Spark版本
  private val restServerEnabled = conf.getBoolean("spark.master.rest.enabled", true)
  private var restServer: Option[StandaloneRestServer] = None
  private var restServerBoundPort: Option[Int] = None
  /**
   * 向Master的ActorSystem注册Master,会先触发onStart方法
   */
  override def onStart(): Unit = {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    //启动WebUi
    webUi = new MasterWebUI(this, webUiPort)
    webUi.bind()
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort
    
    //Master接收到CheckForWorkTimerOut消息后,会匹配调用checkForWorkerTimeOutTask方法处理
    //启动检测Worker是否死亡的定时调度
    checkForWorkerTimeOutTask = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(CheckForWorkerTimeOut)//向自身发送checkForWorkerTimeOutTask消息
      }
     //0毫秒为单位的延迟之前的任务执行,连续执行任务之间的毫秒的时间每隔(60秒),每次调度的任务checkForWorkerTimeOutTask
    }, 0, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    if (restServerEnabled) {
      val port = conf.getInt("spark.master.rest.port", 6066)
      restServer = Some(new StandaloneRestServer(address.host, port, conf, self, masterUrl))
    }
    
    restServerBoundPort = restServer.map(_.start())
    //启动WebUI,masterMetricsSystem,applicationMetricsSystem
    masterMetricsSystem.registerSource(masterSource)
    masterMetricsSystem.start()
    applicationMetricsSystem.start()
    // Attach the master and app metrics servlet handler to the web ui after the metrics systems are
    // started.
    //创建getServletHandlers 并注册到WebUI
    masterMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)
    applicationMetricsSystem.getServletHandlers.foreach(webUi.attachHandler)

    val serializer = new JavaSerializer(conf)
    //选择故障恢复的持久化引擎,选择领导选举代理(leaderElectionAgent),local-cluster模式中将注册MonarchyLeaderAgent
    //因此触发MonarchyLeaderAgent.electedLeader方法
    val (persistenceEngine_, leaderElectionAgent_) = RECOVERY_MODE match {
      case "ZOOKEEPER" =>
        logInfo("Persisting recovery state to ZooKeeper")
        val zkFactory =
          new ZooKeeperRecoveryModeFactory(conf, serializer)
        (zkFactory.createPersistenceEngine(), zkFactory.createLeaderElectionAgent(this))
      case "FILESYSTEM" =>
        val fsFactory =
          new FileSystemRecoveryModeFactory(conf, serializer)
        (fsFactory.createPersistenceEngine(), fsFactory.createLeaderElectionAgent(this))
      case "CUSTOM" =>
        val clazz = Utils.classForName(conf.get("spark.deploy.recoveryMode.factory"))
        val factory = clazz.getConstructor(classOf[SparkConf], classOf[Serializer])
          .newInstance(conf, serializer)
          .asInstanceOf[StandaloneRecoveryModeFactory]
        (factory.createPersistenceEngine(), factory.createLeaderElectionAgent(this))
      case _ =>
        (new BlackHolePersistenceEngine(), new MonarchyLeaderAgent(this))
    }
    persistenceEngine = persistenceEngine_
    leaderElectionAgent = leaderElectionAgent_
  }

  override def onStop() {
    masterMetricsSystem.report()
    applicationMetricsSystem.report()
    // prevent the CompleteRecovery message sending to restarted master
    //防止完成恢复发送重新启动Master
    if (recoveryCompletionTask != null) {
      recoveryCompletionTask.cancel(true)
    }
    if (checkForWorkerTimeOutTask != null) {
      checkForWorkerTimeOutTask.cancel(true)
    }
    forwardMessageThread.shutdownNow()
    webUi.stop()
    restServer.foreach(_.stop())
    masterMetricsSystem.stop()
    applicationMetricsSystem.stop()
    persistenceEngine.close()
    leaderElectionAgent.stop()
  }
  /**
   * onStart 触发electedLeader方法,向Master发送ElectedLeader消息
   */
  override def electedLeader() {
    self.send(ElectedLeader)
  }

  override def revokedLeadership() {
    self.send(RevokedLeadership)
  }

  override def receive: PartialFunction[Any, Unit] = {
    case ElectedLeader => {//收到ElectedLeader消息后会进行选举操作,处理当前的Master被选举为Leader
      //读取集群当前运行的Application,Driver,Client和Worker
      val (storedApps, storedDrivers, storedWorkers) = persistenceEngine.readPersistedData(rpcEnv)
     //如果所有的元数据都是空的,那么就不需要恢复
      state = if (storedApps.isEmpty && storedDrivers.isEmpty && storedWorkers.isEmpty) {
        RecoveryState.ALIVE
      } else {
        RecoveryState.RECOVERING
      }
      logInfo("I have been elected leader! New state: " + state)
      if (state == RecoveryState.RECOVERING) {
        //开始恢复集群之前状态,恢复实际上就是通知Application和Worker,Master已经更改
        beginRecovery(storedApps, storedDrivers, storedWorkers)
        //在WORKER_TIMEOUT_MS(60)秒后,尝试将恢复标记为完成
        recoveryCompletionTask = forwardMessageThread.schedule(new Runnable {
          override def run(): Unit = Utils.tryLogNonFatalError {
            self.send(CompleteRecovery)
          }
        }, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }
    }
    //恢复已经结束
    case CompleteRecovery => completeRecovery()

    case RevokedLeadership => {
      logError("Leadership has been revoked -- master shutting down.")
      System.exit(0)
    }
    /**
     * Master收到RegisterWorker消息后的处理步骤:
     * 1)创建WorkerInfo
     * 2)registerWorker注册WorkerInfo,其实就是将添加到workers[HashSet]中,并且更新worker Id与Worker以及worker addess与worker的映射关系
     * 3)向Worker发送RegisteredWorker消息,表示注册完成
     * 4)调用schedule方法进行资源调度   
     */
    case RegisterWorker(
        id, workerHost, workerPort, workerRef, cores, memory, workerUiPort, publicAddress) => {
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format(
        workerHost, workerPort, cores, Utils.megabytesToString(memory)))
        // 如果收到消息的Master处于Standby备用状态,则不做任何响应
      if (state == RecoveryState.STANDBY) {         
        // ignore, don't send response
      } else if (idToWorker.contains(id)) {
        //判断是否存在重复的Worker ID,如果是则拒绝注册
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        /**如果当前Master是Active的Master,同时不存在重复的Worder ID则
         		1)抽取注册上来的Worker的消息并加以保存,为今后Master在异常退出后的恢复做准备
         		2)发送响应给Worker 确认注册
         		3)调用Schedule函数,将已经提交但没有分配资源的Aplication分发到新加入的Worker Node         
         **/
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          workerRef, workerUiPort, publicAddress)
        if (registerWorker(worker)) {
          //注册上来的Worker的消息并加以保存,为今后Master在异常退出后的恢复做准备
          persistenceEngine.addWorker(worker)
          //向Worker发送RegisteredWorker消息,表示注册完成
          workerRef.send(RegisteredWorker(self, masterWebUiUrl))
          //调用Schedule函数,将已经提交但没有分配资源的Aplication分发到新加入的Worker Node 
          schedule()
        } else {
          val workerAddress = worker.endpoint.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }
    }
    /**
     * Master接收到RegisterApplication消息后,处理步骤
     * 1)创建ApplicationInfo
     * 2)注册ApplicationInfo
     * 3)向ClientEndpoint发送RegisteredAppliction消息
     * 4)调用schedule()执行调度
     */
    case RegisterApplication(description, driver) => {
      // TODO Prevent repeated registrations from some driver
      if (state == RecoveryState.STANDBY) {
        // ignore, don't send response
        // 恢复状态,不发送任务信息       
      } else {
        logInfo("Registering app " + description.name)
        //创建Application,会调用Application.init方法
        val app = createApplication(description, driver)
        //注册Application的过程
        //将applicationinfo加入缓存，将application加入等待调度队列---waitingApps
        registerApplication(app)
        logInfo("Registered app " + description.name + " with ID " + app.id)
        //持久化app的元数据信息,可以选择持久化到ZooKeeper,本地文件系统,或者不持久化
        persistenceEngine.addApplication(app)
        //反向，向backend发送消息。 向ClientEndpoint发送RegisteredAppliction消息
        driver.send(RegisteredApplication(app.id, self))
        //为处于待分配资源的Application分配资源。在每次有新的Application加入或者新的资源加入时都会调用schedule进行调度
        schedule()
      }
    }
    //Mater收到Worker上报来的消息,会将失联的executor通知给Application Driver
  /**
   * launchExecutor分配资源,启动Executor功能如下
   * 1)将ExecutorDesc添加到WorkerInfo的executors缓存中,并更新Worker已经使用的CPU核数和内存大小
   * 2)向Worker发送LaunchExecutor消息,运行Executor
   * 3)向AppClient发送ExecutorAdded消息,AppClient收到后,向Master发送ExecutorStateChanged消息
   * 4)Master收到ExecutorStateChanged消息后将DriverEndpoint发送ExecutorUpdated消息,用于更新Driver上有关Executor
   */
    case ExecutorStateChanged(appId, execId, state, message, exitStatus) => {
      /**
       * 处理步骤如下:
       * 1)找到占有Executor的Application的ApplicationInfo,以及Executor对应的ExecutorInfo
       * 2)将ExecutorInfo的状态改为Exited
       * 3)Exited也是属于Executor完成状态,所以重新调用schedule给Application进行资源调度
       */

        //找到executor对应的app，之后反过来通过app内部的executors缓存获取executor信息
      val execOption = idToApp.get(appId).flatMap(app => app.executors.get(execId))
      execOption match {
          //如果有值
        case Some(exec) => {
          //设置executor当前的状态
          val appInfo = idToApp(appId)
          exec.state = state
          if (state == ExecutorState.RUNNING) { appInfo.resetRetryCount() }
          //向Driver发送ExecutorUpdated消息
          exec.application.driver.send(ExecutorUpdated(execId, state, message, exitStatus))

          //判断，如果状态是已完成的
          if (ExecutorState.isFinished(state)) {
            // Remove this executor from the worker and app
            //从Worker和app中删除这个executor
            logInfo(s"Removing executor ${exec.fullId} because it is $state")
            // If an application has already finished, preserve its
            // state to display its information properly on the UI
            //如果一个应用程序已经完成,保存它,
            //状态,正确显示其信息在用户界面上
            if (!appInfo.isFinished) {
              appInfo.removeExecutor(exec)
            }
            exec.worker.removeExecutor(exec)
            //如果executor的退出状态是非正常的
            val normalExit = exitStatus == Some(0)
            // Only retry certain number of times so we don't go into an infinite loop.
            // 只有重试一定数量的时间,所以我们不进入一个无限循环
            if (!normalExit) {
              if (appInfo.incrementRetryCount() < ApplicationState.MAX_NUM_RETRY) {
                //重新调度
                schedule()
              } else {
                //否则， 进行removeapplication操作
                val execs = appInfo.executors.values
                if (!execs.exists(_.state == ExecutorState.RUNNING)) {
                  logError(s"Application ${appInfo.desc.name} with ID ${appInfo.id} failed " +
                    s"${appInfo.retryCount} times; removing it")
                  removeApplication(appInfo, ApplicationState.FAILED)
                }
              }
            }
          }
        }
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        case None =>
          logWarning(s"Got status update for unknown executor $appId/$execId")
      }
    }

    case DriverStateChanged(driverId, state, exception) => {
      state match {
          //如果driver的状态为错误、完成、被杀掉、失败，那么就移除driver
        case DriverState.ERROR | DriverState.FINISHED | DriverState.KILLED | DriverState.FAILED =>
          removeDriver(driverId, state, exception)
        case _ =>
          throw new Exception(s"Received unexpected state update for driver $driverId: $state")
      }
    }
    /**
     * Master收到Heartbeat消息后的实现步骤
     */
    case Heartbeat(workerId, worker) => {
      idToWorker.get(workerId) match {
        case Some(workerInfo) =>
          //更新workerInfo.lastHeartbeat,即最后一次接收到心跳的时间戳
          workerInfo.lastHeartbeat = System.currentTimeMillis()
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        case None =>
          if (workers.map(_.id).contains(workerId)) {
            //如果worker的id与Worker的映射关系(idToWorker)中找不到匹配的Worker
            //但是Worker的缓存(workers)的缓存中却存在此Id
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " Asking it to re-register.")
            //向Worker发送ReconnectWorker消息
            worker.send(ReconnectWorker(masterUrl))
          } else {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " This worker was never registered, so ignoring the heartbeat.")
          }
      }
    }
/**
 * AppClient收到后改变其保存的Master的信息,包括URL和Master actor的信息,
 * 回复MasterChangeAcknowledged(appId)
 */
    case MasterChangeAcknowledged(appId) => {
      idToApp.get(appId) match {
        case Some(app) =>
          logInfo("Application has been re-registered: " + appId)
          //Master收到后通过appId后将Application的状态置为WAITING
          app.state = ApplicationState.WAITING
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        case None =>
          logWarning("Master change ack from unknown app: " + appId)
      }
    //检查如果所有的worker和Application的状态都不是UNKNOWN,那么恢复结束,调用completeRecovery()
      if (canCompleteRecovery) { completeRecovery() }
    }
/**
 * 恢复Worker的步骤:
 * 1)重新注册Worker（实际上是更新Master本地维护的数据结构）,置状态为UNKNOWN
 * 2)向Worker发送Master Changed的消息
 * 3)Worker收到消息后,向Master回复WorkerSchedulerStateResponse消息,并通过该消息上报executor和driver的信息
 * 4)Master收到WorkerSchedulerStateResponse消息后,会置该Worker的状态为ALIVE,并且会检查该Worker上报的信息是否与自己从ZK中获取的数据一致,包括executor和driver。
 *         一致的executor和driver将被恢复。对于Driver,其状态被置为RUNNING。
 */
    case WorkerSchedulerStateResponse(workerId, executors, driverIds) => {
      idToWorker.get(workerId) match {
        case Some(worker) =>
          logInfo("Worker has been re-registered: " + workerId)
          worker.state = WorkerState.ALIVE //激活状态

          val validExecutors = executors.filter(exec => idToApp.get(exec.appId).isDefined)
          for (exec <- validExecutors) {
            val app = idToApp.get(exec.appId).get
            val execInfo = app.addExecutor(worker, exec.cores, Some(exec.execId))
            worker.addExecutor(execInfo)
            execInfo.copyState(exec)
          }

          for (driverId <- driverIds) {
            drivers.find(_.id == driverId).foreach { driver =>
              driver.worker = Some(worker)
              driver.state = DriverState.RUNNING
              worker.drivers(driverId) = driver
            }
          }
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        case None =>
          logWarning("Scheduler state from unknown worker: " + workerId)
      }

      if (canCompleteRecovery) { completeRecovery() }
    }

    case UnregisterApplication(applicationId) =>
      logInfo(s"Received unregister request from application $applicationId")
      idToApp.get(applicationId).foreach(finishApplication)
    //Master是如何确认Worker一直是活着还是挂掉呢?
     //启动检测Worker是否死亡的定时调度
    case CheckForWorkerTimeOut => {
      timeOutDeadWorkers()
    }
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    //接收Client发送RequestSubmitDriver消息
    case RequestSubmitDriver(description) => {      
      if (state != RecoveryState.ALIVE) {
        //如果master不是active,返回错误
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only accept driver submissions in ALIVE state."
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        context.reply(SubmitDriverResponse(self, false, None, msg))
      } else {
        //否则创建driver,返回成功的消息
        logInfo("Driver submitted " + description.command.mainClass)
        //创建Driver
        val driver = createDriver(description)
        //持久化Driver
        persistenceEngine.addDriver(driver)
        //等待启动Driver
        waitingDrivers += driver
        drivers.add(driver)
        schedule()//调度

        // TODO: It might be good to instead have the submission client poll the master to determine
        //       the current status of the driver. For now it's simply "fire and forget".

        context.reply(SubmitDriverResponse(self, true, Some(driver.id),
          s"Driver successfully submitted as ${driver.id}"))
      }
    }

    case RequestKillDriver(driverId) => {
      if (state != RecoveryState.ALIVE) {
        //如果master不是active,返回错误
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          s"Can only kill drivers in ALIVE state."
        context.reply(KillDriverResponse(self, driverId, success = false, msg))
      } else {
     
        logInfo("Asked to kill driver " + driverId)
        val driver = drivers.find(_.id == driverId)
        driver match {
          case Some(d) =>
            if (waitingDrivers.contains(d)) {
              //如果driver仍然在等待队列,从等待队列删除并且更新driver状态为KILLED
              waitingDrivers -= d
              //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
              self.send(DriverStateChanged(driverId, DriverState.KILLED, None))
            } else {
              // We just notify the worker to kill the driver here. The final bookkeeping occurs
              // on the return path when the worker submits a state change back to the master
              // to notify it that the driver was successfully killed.
              //我们只是通知通知worker在这里杀死driver,当worker将状态更改提交给master以通知driver被成功杀死时,最终簿记发生在返回路径上。
              //通知worker kill driver id的driver。结果会由workder发消息给master ! DriverStateChanged
              d.worker.foreach { w =>
                w.endpoint.send(KillDriver(driverId))
              }
            }
            // TODO: It would be nice for this to be a synchronous response
            //注意,此时driver不一定被kill,master只是通知了worker去kill driver。
            val msg = s"Kill request for $driverId submitted"
            logInfo(msg)
            context.reply(KillDriverResponse(self, driverId, success = true, msg))
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
          case None =>
            // driver已经被kill,直接返回结果
            val msg = s"Driver $driverId has already finished or does not exist"
            logWarning(msg)
            context.reply(KillDriverResponse(self, driverId, success = false, msg))
        }
      }
    }

    case RequestDriverStatus(driverId) => {
      if (state != RecoveryState.ALIVE) {
        val msg = s"${Utils.BACKUP_STANDALONE_MASTER_PREFIX}: $state. " +
          "Can only request driver status in ALIVE state."
        context.reply(
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
          DriverStatusResponse(found = false, None, None, None, Some(new Exception(msg))))
      } else {
        // 查找请求的driver,如果找到则返回driver的状态
        (drivers ++ completedDrivers).find(_.id == driverId) match {
          case Some(driver) =>
            context.reply(DriverStatusResponse(found = true, Some(driver.state),
              driver.worker.map(_.id), driver.worker.map(_.hostPort), driver.exception))
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
          case None =>
            context.reply(DriverStatusResponse(found = false, None, None, None, None))
        }
      }
    }

    case RequestMasterState => {
       //向sender返回master的状态
      context.reply(MasterStateResponse(
        address.host, address.port, restServerBoundPort,
        workers.toArray, apps.toArray, completedApps.toArray,
        drivers.toArray, completedDrivers.toArray, state))
    }
  /**
   * 首先会启动
   */
    case BoundPortsRequest => {
      context.reply(BoundPortsResponse(address.port, webUi.boundPort, restServerBoundPort))
    }

    case RequestExecutors(appId, requestedTotal) =>
      context.reply(handleRequestExecutors(appId, requestedTotal))

    case KillExecutors(appId, executorIds) =>
      val formattedExecutorIds = formatExecutorIds(executorIds)
      context.reply(handleKillExecutors(appId, formattedExecutorIds))
  }
/**
 * Akka的通信机制保证当相互通信的任意一方异常退出,另一方都会收到onDisconnected,Master正是处理onDisconnected
 * 消息时移除已经停止的DriverApplication
 */
  override def onDisconnected(address: RpcAddress): Unit = {
    // The disconnected client could've been either a worker or an app; remove whichever it was
    //断开连接的客户端可能是worker或应用程序; 删除它是哪个
    logInfo(s"$address got disassociated, removing it.")
    //注意传递removeWorker方法,不需WorkerInfo参数
    addressToWorker.get(address).foreach(removeWorker)
    addressToApp.get(address).foreach(finishApplication)
    if (state == RecoveryState.RECOVERING && canCompleteRecovery) { completeRecovery() }
  }

  private def canCompleteRecovery =
    workers.count(_.state == WorkerState.UNKNOWN) == 0 &&
      apps.count(_.state == ApplicationState.UNKNOWN) == 0

  private def beginRecovery(storedApps: Seq[ApplicationInfo], storedDrivers: Seq[DriverInfo],
      storedWorkers: Seq[WorkerInfo]) {
    for (app <- storedApps) {// 逐个恢复Application
      logInfo("Trying to recover app: " + app.id)
      try {
        registerApplication(app)
        //ApplicationState.UNKNOWN置待恢复的Application的状态为UNKNOWN,向AppClient发送MasterChanged的消息
        app.state = ApplicationState.UNKNOWN
        //向AppClient发送Master变化的消息,AppClient会回复MasterChangeAcknowledged
        app.driver.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("App " + app.id + " had exception on reconnect")
      }
    }

    for (driver <- storedDrivers) {
      // Here we just read in the list of drivers. Any drivers associated with now-lost workers
      // will be re-launched when we detect that the worker is missing.
      //在这里我们刚刚读取驱动程序列表,当我们检测到Worker失踪时,与现在失去Worker有关的任何driver将重新启动,
      //在Worker恢复后,Worker会主动上报运行其上的executors和drivers从而使得Master恢复executor和driver的信息。
      drivers += driver
    }

    for (worker <- storedWorkers) {//逐个恢复Worker
      logInfo("Trying to recover worker: " + worker.id)
      try {
        registerWorker(worker)//重新注册Worker,置状态为UNKNOWN
        worker.state = WorkerState.UNKNOWN
        //向Worker发送Master变化Changed的消息,Worker会回复WorkerSchedulerStateResponse
        worker.endpoint.send(MasterChanged(self, masterWebUiUrl))
      } catch {
        case e: Exception => logInfo("Worker " + worker.id + " had exception on reconnect")
      }
   
    }
  }
/**
 * 调用时机
 * 1. 在恢复开始后的60s会被强制调用
 * 2. 在每次收到AppClient和Worker的消息回复后会检查如果Application和worker的状态都不为UNKNOWN,则调用
  * 用途：1、用于主备切换。 2、xxx
 */
  private def completeRecovery() {
    // Ensure "only-once" recovery semantics using a short synchronization period.
    //使用短同步期确保只“一次”恢复语义
    if (state != RecoveryState.RECOVERING) { return }
    state = RecoveryState.COMPLETING_RECOVERY
    //将所有未响应（UNKNOWN）的Worker和Application删除
    // Kill off any workers and apps that didn't respond to us.
    // 删除在60s内没有回应的app和worker
    workers.filter(_.state == WorkerState.UNKNOWN).foreach(removeWorker)
    apps.filter(_.state == ApplicationState.UNKNOWN).foreach(finishApplication)
    //对于未分配Woker的Driver client(有可能Worker已经死掉)
    //确定是否需要重新启动
    // Reschedule drivers which were not claimed by any workers
    drivers.filter(_.worker.isEmpty).foreach { d => //如果driver的worker为空,则relaunchDriver。
      logWarning(s"Driver ${d.id} was not found after master recovery")
      if (d.desc.supervise) {//需要重新启动Driver Client
        logWarning(s"Re-launching ${d.id}")
        relaunchDriver(d)
      } else {//将没有设置重启的Driver Client删除
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        removeDriver(d.id, DriverState.ERROR, None)
        logWarning(s"Did not re-launch ${d.id} because it was not supervised")
      }
    }
    //设置Master的状态为ALIVE,此后Master开始正常工作
    state = RecoveryState.ALIVE
    schedule()//开始新一轮的资源调度
    logInfo("Recovery complete - resuming operations!")
  }

  /**
   * 
   * Schedule executors to be launched on the workers.
   * 在Worker启动调用执行器,
   * Returns an array containing number of cores assigned to each worker.
   * 返回一个包含分配给每个Worker的内核数的数组
   * There are two modes of launching executors. The first attempts to spread out an application's
   * 有两种启动执行模式,第一种我们应该保持对这个工作节点调度执行直到使用它的所有资源,而第二个则相反
   * executors on as many workers as possible, while the second does the opposite (i.e. launch them
   * on as few workers as possible). The former is usually better for data locality purposes and is
   * 前者是数据局部性(内存中数据)的目的通常是更好是默认
   * the default.
   *
   * The number of cores assigned to each executor is configurable. When this is explicitly set,
   * 分配给每个执行者的内核的数量是可配置的,当这是明确的设置
   * multiple executors from the same application may be launched on the same worker if the worker
   * 自同一个应用程序相同的Woker可能启动多个执行者,如果Worker有足够的内核和内存
   * has enough cores and memory. Otherwise, each executor grabs all the cores available on the
   * 否则,默认Worker每个执行者都获取的所有可用的内核,
   * worker by default, in which case only one executor may be launched on each worker.
   * 在这种情况下,每个Worker只有一个执行者可能会启动
   *
   * It is important to allocate coresPerExecutor on each worker at a time (instead of 1 core
   * 这是对每个Woker分配coresperexecutor重要的一次(而不是一次1个内核)
   * at a time). Consider the following example: cluster has 4 workers with 16 cores each.
   * 考虑下面的例子:集群有4节点,每个节点16个内核,用户请求3执行者(Spark最大内核48,执行者内核16)
   * User requests 3 executors (spark.cores.max = 48, spark.executor.cores = 16). If 1 core is
   * 如果一个内核分配一次,每个Worker的12个内核将被分配给每一个执行者
   * allocated at a time, 12 cores from each worker would be assigned to each executor.
   *Since 12 < 16,没有执行者将启动
   * Since 12 < 16, no executors would launch [SPARK-8881].
   * 返回为每个Worker上分配的cores的数组
   */
  private def scheduleExecutorsOnWorkers(
      app: ApplicationInfo,
      usableWorkers: Array[WorkerInfo],
      spreadOutApps: Boolean): Array[Int] = {
    //Application每一个Executor进程的core个数
    val coresPerExecutor = app.desc.coresPerExecutor
    //默认最小为每个executor分配一个core
    val minCoresPerExecutor = coresPerExecutor.getOrElse(1)    
    val oneExecutorPerWorker = coresPerExecutor.isEmpty
    //每个Executor需要的memory
    val memoryPerExecutor = app.desc.memoryPerExecutorMB
    val numUsable = usableWorkers.length
    //已经给每个Worker上分配的core
    val assignedCores = new Array[Int](numUsable) // Number of cores to give to each worker
    //每个Worker的分配新的执行者
    val assignedExecutors = new Array[Int](numUsable) // Number of new executors on each worker
    //求最小值,
    //为啥要求最小值：因为可能我们的程序需要1000个cores,但是集群中只有100个cores。
    //所以只能先分配100个cores,所以就要增加批次    
    var coresToAssign = math.min(app.coresLeft, 
        //app.coresLeft程序需要的cores,可用的Worker上面的free cores总和
        usableWorkers.map(_.coresFree).sum)
    /** Return whether the specified worker can launch an executor for this app.筛选条件 */
    def canLaunchExecutor(pos: Int): Boolean = {
      //必须要大于等于,因为如果默认一个cores都不能满足的话,无法启动了。
      val keepScheduling = coresToAssign >= minCoresPerExecutor   
      val enoughCores = usableWorkers(pos).coresFree - assignedCores(pos) >= minCoresPerExecutor
      // If we allow multiple executors per worker, then we can always launch new executors.
      //如果我们允许每个Worker多个执行器,然后我们可以启动新的执行者
      // Otherwise, if there is already an executor on this worker, just give it more cores.
      //否则,如果这个Woker已经有一个执行者,只是给它更多的内核
      val launchingNewExecutor = !oneExecutorPerWorker || assignedExecutors(pos) == 0
      if (launchingNewExecutor) {
        //具体executor上分配的内存
        val assignedMemory = assignedExecutors(pos) * memoryPerExecutor
        val enoughMemory = usableWorkers(pos).memoryFree - assignedMemory >= memoryPerExecutor
        val underLimit = assignedExecutors.sum + app.executors.size < app.executorLimit
        keepScheduling && enoughCores && enoughMemory && underLimit
      } else {
        // We're adding cores to an existing executor, so no need
	      //我们正在向一个存在的执行者添加内核,所以不需要检查内存和执行器限制
        // to check memory and executor limits
        //如果Worker上的executor已经存在,可用直接往executor上增加cores
        keepScheduling && enoughCores
      }
    }

    // Keep launching executors until no more workers can accommodate any
    //保持启动执行者直到没有更多Worker可以容纳更多的执行者
    // more executors, or if we have reached this application's limits
    //或者如果我们已经达到了这个应用程序的限制
    //根据filter就过滤出来满足在Worker上launchExecutor的条件
    var freeWorkers = (0 until numUsable).filter(canLaunchExecutor)
    while (freeWorkers.nonEmpty) {//可用的Worker不是空的话,就执行下面的循环。
      //如果不是spreadOutApps的时候,每次都会给executor增加一个core,
      //会一直循环当前程序的executor上的freeCores,所以会占用本机器上的尽可能多的cores
      freeWorkers.foreach { pos =>
        var keepScheduling = true      
        while (keepScheduling && canLaunchExecutor(pos)) {
          coresToAssign -= minCoresPerExecutor
          assignedCores(pos) += minCoresPerExecutor
           // If we are launching one executor per worker, then every iteration assigns 1 core
	        //如果是每个Worker下面只能够为当前的应用程序分配一个Executor的话,每次迭代执行者只分配一个内核
          // to the executor. Otherwise, every iteration assigns cores to a new executor.
	        //否则,每一次迭代将内核分配给一个新的执行者
          if (oneExecutorPerWorker) {
            assignedExecutors(pos) = 1
          } else {
            assignedExecutors(pos) += 1
          }
          // Spreading out an application means spreading out its executors across as	
          // many workers as possible. If we are not spreading out, then we should keep
          // scheduling executors on this worker until we use all of its resources.
	        //如果spreadOut为false,worker节点调度直到使用它的所有的内核资源
          // Otherwise, just move on to the next worker.否则,就转移到下一个worker节点均匀分配内核
          //如果spreadOutApps为false,会尽可能用当前的机器去处理程序的一切的cores需求,也就是executor会占用尽可能多的cores	 
          if (spreadOutApps) {
            keepScheduling = false
          }
        }
      }
      freeWorkers = freeWorkers.filter(canLaunchExecutor)
    }
    assignedCores
  }

  /**
   * Schedule and launch executors on workers
   * 对资源的逻辑分配主要指对Worker的CPU核数的分配,即将当前Application的CPU核数需要求分配 到所有的Worker上,
   * 那些内存不满足ApplicationDescription中指定的memory-perslave变更的大小的会被过滤掉,
   * 
   * 给Application分配CPU核数处理步骤如下
   * 1)过虑出所有可用的Worker条件
   * 2)处于激活状态(ALIVE)
   * 3)空闲内存大于等于Apllication在每个Worker上需要的内存(memoryPerSlave)
   * 4)还没有为此Application运行过Executor
   * 5)对于过虑得到的Worker按照其空闲内核数倒序排列,实际需要分配的内核数,从Application需要的内核数与所有过滤后的 Worker的空闲内核数的总和两者中取最小值
   *  如果需要分配的内核数大于0,则逐个从各个Worker中给Application分配1个内核.当所有Worker都分配过后,需要分配的内核依然大于0
   *  则从头一个worker再次分配,如此往复,直到Applcation需要的内核数为0
    */
  private def startExecutorsOnWorkers(): Unit = {
    // Right now this is a very simple FIFO scheduler. We keep trying to fit in the first app
    // in the queue, then the second app, etc.
    //app.coresLeft是Application需要的内核数coresLeft
    // 遍历一下waitingApps中的applicationinfo，并过滤出还有需要调度的core的application
    for (app <- waitingApps if app.coresLeft > 0) {//使用FIFO(先进先出)的方式为等待的app分配资源,
      //为应用程序具体分配Executor之前要判断应用程序是否还需要分配cores,如果不需要,则不会为应用程序分配Executor
      val coresPerExecutor: Option[Int] = app.desc.coresPerExecutor //获得每个Executor上分配多少个cores
      // Filter out workers that don't have enough resources to launch an executor
    /**给Application分配CPU核数处理步骤如下
     * 1)过虑出所有可用的Worker处于激活状态(ALIVE)
     * 2)空闲内存大于等于Apllication在每个Worker上需要的内存(memoryPerSlave)
     * 3)Worker可使用CPU核数大于或等于Application分配CPU核数过虑得到的Worker
     * 4)按照其空闲CUP内核数倒序排列,优先选择可用core数多的.
     */

      //过滤出状态为alive的worker，再次过滤可以被application使用的worker，之后按照剩余cpu数量倒序
      val usableWorkers = workers.toArray.filter(_.state == WorkerState.ALIVE)
        .filter(worker => worker.memoryFree >= app.desc.memoryPerExecutorMB &&
          worker.coresFree >= coresPerExecutor.getOrElse(1))//至少大于一个
        .sortBy(_.coresFree).reverse
      
      //scheduleExecutorsOnWorkers返回为每个Worker上分配的cores的数组
      val assignedCores = scheduleExecutorsOnWorkers(app, usableWorkers, spreadOutApps)

      // Now that we've decided how many cores to allocate on each worker, let's allocate them
      //计算资源物理分配,即计算资源物理分配是指给Application物理分配Worker的内存以及核数
      for (pos <- 0 until usableWorkers.length if assignedCores(pos) > 0) {     
        //然后就是分配了,准备好具体要为当前应用程序分配的Executor信息后,
        //具体Master要通过远程通信发指令给Worker来具体启动ExecutorBackEnd进程
        allocateWorkerResourceToExecutors(
          app, assignedCores(pos), coresPerExecutor, usableWorkers(pos))
      }
    }
  }

  /**
   * 计算资源物理分配,即计算资源物理分配是指给Application物理分配Worker的内存以及核数.
   * 由于在逻辑分配的时候已经确定了每个Worker分配给Application的核数,并且这些Worker也都满足Application的内存需要
   * 所以可以放心地进行物理分配 了.
   * Allocate a worker's resources to one or more executors.
    * 将一名worker的资源分配给一名或多名executors
   * @param app the info of the application which the executors belong to 执行者所属的应用程序的信息
   * @param assignedCores number of cores on this worker for this application 此应用程序的此worker的核心数
   * @param coresPerExecutor number of cores per executor 每个执行者的核心数
   * @param worker the worker info
   * 
   */
  private def allocateWorkerResourceToExecutors(
      app: ApplicationInfo,
      assignedCores: Int,
      coresPerExecutor: Option[Int],
      worker: WorkerInfo): Unit = {
    // If the number of cores per executor is specified, we divide the cores assigned
    // to this worker evenly among the executors with no remainder.
    // Otherwise, we launch a single executor that grabs all the assignedCores on this worker.
    //如果指定了每个executor的核心数量,我们将分配给此worker的核心在没有余数的执行程序中均匀分配,
    //否则,我们启动一个executor,抓取这个worker的所有被分配的CORE
    val numExecutors = coresPerExecutor.map { assignedCores / _ }.getOrElse(1)
    val coresToAssign = coresPerExecutor.getOrElse(assignedCores)
    for (i <- 1 to numExecutors) {    
      //addExecutor逻辑分配CPU核数及内存大小,将ExecutorDesc添加到Application的executors缓存中,增加已经授权得到的内存数
      //给Driver发送一个ExecutorAdded的信息
      val exec = app.addExecutor(worker, coresToAssign)
      //物理分配通过调用launchExecutor,在worker上启动Executor
      launchExecutor(worker, exec)
      //分配executor之后,application就为RUNNING
      app.state = ApplicationState.RUNNING 
    }
  }

  /**
    * 资源调度。重中之重
   * 为处于待分配资源的Application分配资源,每次有新的应用程序或者集群资源状态发生改变的时候
   * （包括Executor增加或者减少、Worker增加或者减少等）
   * Schedule the currently available resources among waiting apps. This method will be called
   * every time a new app joins or resource availability changes.
   * 
   */
  private def schedule(): Unit = {
    if (state != RecoveryState.ALIVE) { return }
    //Master必须是Alive的状态才能进行资源的调度,如果不是Alive的状态,会直接返回
    // Drivers take strict precedence over executors
    //把当前workers这个HashSet的顺序随机打乱
    val shuffledWorkers = Random.shuffle(workers) // Randomization helps balance drivers
    for (worker <- shuffledWorkers if worker.state == WorkerState.ALIVE) {//遍历活着的workers
      //用yarn-cluster才会注册driver，因为standalone和yarn-client模式下都会在本地直接启动driver，
      // 不会来注册driver，更不可能让master调度driver了
      for (driver <- waitingDrivers) {//在等待队列中的Driver会进行资源分配
	        //当前的worker内存和cpu均大于当前driver请求的mem和cpu,启动launchDriver
        if (worker.memoryFree >= driver.desc.mem && worker.coresFree >= driver.desc.cores) {
          //启动Driver 内部实现是发送启动Driver命令给指定Worker,Worker来启动Driver
          launchDriver(worker, driver)
	        //把启动过的Driver从队列移除
          waitingDrivers -= driver
        }
      }
    }
    //application的调度机制（重中之重）
    //根据spreadOutApps的值，1种是把worker的cpu core平均分配给executor，
    // 另外1种是把worker的cpu core集中分配到1个executor上。
    startExecutorsOnWorkers()
  }
/**
 * launchExecutor分配资源,启动Executor功能如下
 * 1)将ExecutorDesc添加到WorkerInfo的executors缓存中,并更新Worker已经使用的CPU核数和内存大小
 * 2)向Worker发送LaunchExecutor消息,运行Executor
 * 3)向AppClient发送ExecutorAdded消息,AppClient收到后,向Master发送ExecutorStateChanged消息
 * 4)Master收到ExecutorStateChanged消息后将DriverEndpoint发送ExecutorUpdated消息,用于更新Driver上有关Executor
 * 
 */

  private def launchExecutor(worker: WorkerInfo, exec: ExecutorDesc): Unit = {
    logInfo("Launching executor " + exec.fullId + " on worker " + worker.id)
    //将executor加入worker内部的缓存
    worker.addExecutor(exec)
    //2)向Worker发送LaunchExecutor消息,运行Executor
    worker.endpoint.send(LaunchExecutor(masterUrl,
      exec.application.id, exec.id, exec.application.desc, exec.cores, exec.memory))
    //3)向Driver发送ExecutorAdded消息,Driver收到后,向Master发送ExecutorStateChanged消息
    exec.application.driver.send(ExecutorAdded(
      exec.id, worker.id, worker.hostPort, exec.cores, exec.memory))
  }
/**
 * 注册WorkerInfo,其实就是将添加到workers[HashSet]中,
 * 并且更新worker Id与Worker以及worker addess与worker的映射关系
 */
  private def registerWorker(worker: WorkerInfo): Boolean = {
    // There may be one or more refs to dead workers on this same node (w/ different ID's),
    // remove them.
    //如果当前Worker不活动,从workers[HashSet]删除worker,则重新注册
    workers.filter { w =>
      (w.host == worker.host && w.port == worker.port) && (w.state == WorkerState.DEAD)
    }.foreach { w =>
      workers -= w
    }

    val workerAddress = worker.endpoint.address
    //已注册WorkerHashMap[RpcAddress, WorkerInfo]是否包涵当前workerAddress
    if (addressToWorker.contains(workerAddress)) {
      val oldWorker = addressToWorker(workerAddress)
      if (oldWorker.state == WorkerState.UNKNOWN) {
        // A worker registering from UNKNOWN implies that the worker was restarted during recovery.
        //从UNKNOWN注册的worker意味着工人在恢复期间重新启动
        // The old worker must thus be dead, so we will remove it and accept the new worker.
        //因此,老worker必须死亡,所以我们会把它删除并接受新的worker。
        removeWorker(oldWorker)
      } else {
        logInfo("Attempted to re-register worker at same address: " + workerAddress)
        return false
      }
    }
    //增加向HashSet增加work信息 
    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }
/**
 * 如果判定结果认为相应的Worder已经不再存活,那么利用removeWorker函数通知Driver Application
 * 如果worker.state不是WorkerState.DEAD,则调用removeWorker方法将WorkerInfo的状态设置DEAD,从idToWorker缓存中移除Worker的Id,
 * 从addressToWorker的缓存中移除WorkerInfo,最后向此WorkerInfo的所有Executor所有服务的Drver application发送ExecutorUpdated消息
 *   更新Executor的状态为Lost,最后使用removeDriver重新调度之前调度给此Worker的Driver
 * 
 */
  private def removeWorker(worker: WorkerInfo) {
    logInfo("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    //设置WorkerInfo状态DEAD不再存活
    worker.setState(WorkerState.DEAD)
    //从idToWorker缓存中移除Worker的Id
    idToWorker -= worker.id
    //从addressToWorker的缓存中移除WorkerInfo
    addressToWorker -= worker.endpoint.address
    //最后向此WorkerInfo的所有Executor所有服务的Drver application发送ExecutorUpdated消息
    //更新Executor的状态为Lost(丢失)
    for (exec <- worker.executors.values) {
      logInfo("Telling app of lost executor: " + exec.id)
      exec.application.driver.send(ExecutorUpdated(
      //ExecutorState.LOST标记丢失,即Worker异常退出
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        exec.id, ExecutorState.LOST, Some("worker lost"), None))
      exec.application.removeExecutor(exec)
    }
    //WorkerInfo最后使用removeDriver重新调度之前调度给此Worker的Driver
    for (driver <- worker.drivers.values) {
      if (driver.desc.supervise) {//drvier挂掉之后可以自动重启
        logInfo(s"Re-launching ${driver.id}")
        //重新调度之前调度给此Worker的Driver
        relaunchDriver(driver)
      } else {
        logInfo(s"Not re-launching ${driver.id} because it was not supervised")
        //removeDriver重新调度之前调度给此Worker的Driver
        //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
        removeDriver(driver.id, DriverState.ERROR, None)
      }
    }
    persistenceEngine.removeWorker(worker)
  }

  private def relaunchDriver(driver: DriverInfo) {
    //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
    driver.worker = None
    driver.state = DriverState.RELAUNCHING
    waitingDrivers += driver
    schedule()
  }
/**
 * 创建Application时,调用init方法
 */
  private def createApplication(desc: ApplicationDescription, driver: RpcEndpointRef):
      ApplicationInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    new ApplicationInfo(now, newApplicationId(date), desc, date, driver, defaultCores)
  }
/**
 * 注册Application过程步骤
 * 1)向applicationMetricsSystem注册applicationMSource,与注册ExecutorSource是一样的
 * 2)注册ApplicationInfo,并且更它与App id,app driver,app address的关系
 * 
 */
  private def registerApplication(app: ApplicationInfo): Unit = {
    val appAddress = app.driver.address
    //判断addressToApp是否包含 appAddress，如果包含则无需重新注册app。
    if (addressToApp.contains(appAddress)) {
      logInfo("Attempted to re-register application at same address: " + appAddress)
      return
    }
    //向applicationMetricsSystem注册applicationMSource
    applicationMetricsSystem.registerSource(app.appSource)
    //保存到master维护的成员变量中
    apps += app
    //一个HashMap Appid-》 ApplicationInfo
    idToApp(app.id) = app
    //HashMap用保存RpcEndpointRef,driver引用 -> ApplicationInfo 
    endpointToApp(app.driver) = app
    //把appAddress添加到addressToApp[HashMap]中
    addressToApp(appAddress) = app
    //等待被调度的Application
    waitingApps += app
  }

  private def finishApplication(app: ApplicationInfo) {
    removeApplication(app, ApplicationState.FINISHED)
  }

  def removeApplication(app: ApplicationInfo, state: ApplicationState.Value) {
    if (apps.contains(app)) {
      logInfo("Removing app " + app.id)
      apps -= app
      idToApp -= app.id
      endpointToApp -= app.driver
      addressToApp -= app.driver.address
      if (completedApps.size >= RETAINED_APPLICATIONS) {
        val toRemove = math.max(RETAINED_APPLICATIONS / 10, 1)
        completedApps.take(toRemove).foreach( a => {
          appIdToUI.remove(a.id).foreach { ui => webUi.detachSparkUI(ui) }
          applicationMetricsSystem.removeSource(a.appSource)
        })
        completedApps.trimStart(toRemove)
      }
      completedApps += app // Remember it in our history
      waitingApps -= app

      // If application events are logged, use them to rebuild the UI
      //如果应用程序事件被记录,请使用它们来重建UI
      rebuildSparkUI(app)

      for (exec <- app.executors.values) {
        killExecutor(exec)
      }
      app.markFinished(state)
      if (state != ApplicationState.FINISHED) {
        app.driver.send(ApplicationRemoved(state.toString))
      }
      persistenceEngine.removeApplication(app)
      //重新执行调度
      schedule()

      // Tell all workers that the application has finished, so they can clean up any app state.
      //告诉所有workers的应用程序已经完成，所以他们可以清理任何应用程序状态
      workers.foreach { w =>
        w.endpoint.send(ApplicationFinished(app.id))
      }
    }
  }

  /**
   * Handle a request to set the target number of executors for this application.
   * 处理请求以设置此应用程序的执行程序的目标数量。处理一请求设置应用的executors数
   * If the executor limit is adjusted upwards, new executors will be launched provided
   * that there are workers with sufficient resources. If it is adjusted downwards, however,
   * we do not kill existing executors until we explicitly receive a kill request.
    *
    * 如果workers的限制向上调整,新的workers将被启动,只要有workers有足够的资源,
    * 但是，如果它向下调整，我们不会杀死现有的执行者，直到我们明确接收到kill请求。
   * @return whether the application has previously registered with this Master.
    *         该应用程序是否以前已向该主服务器注册
   */
  private def handleRequestExecutors(appId: String, requestedTotal: Int): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requested to set total executors to $requestedTotal.")
        appInfo.executorLimit = requestedTotal
        schedule()
        true
      //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
      case None =>
        logWarning(s"Unknown application $appId requested $requestedTotal total executors.")
        false
    }
  }

  /**
   * Handle a kill request from the given application.
   * 处理给定应用程序的kill请求
   * This method assumes the executor limit has already been adjusted downwards through
   * a separate [[RequestExecutors]] message, such that we do not launch new executors
   * immediately after the old ones are removed.
    *
    * 该方法假定执行者限制已经通过单独的[[RequestExecutors]]消息向下调整,
    * 这样我们不会在删除旧的执行器后立即启动新的执行程序
   *
   * @return whether the application has previously registered with this Master.
    *         该应用程序是否以前已向该主服务器注册
   */
  private def handleKillExecutors(appId: String, executorIds: Seq[Int]): Boolean = {
    idToApp.get(appId) match {
      case Some(appInfo) =>
        logInfo(s"Application $appId requests to kill executors: " + executorIds.mkString(", "))
        val (known, unknown) = executorIds.partition(appInfo.executors.contains)//第一部分为满足条件p的元素,第二部分为不满足条件p的元素
        known.foreach { executorId =>
          val desc = appInfo.executors(executorId)
          appInfo.removeExecutor(desc)
          killExecutor(desc)
        }
        if (unknown.nonEmpty) {
          logWarning(s"Application $appId attempted to kill non-existent executors: "
            + unknown.mkString(", "))
        }
        schedule()
        true
      //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
      case None =>
        logWarning(s"Unregistered application $appId requested us to kill executors!")
        false
    }
  }

  /**
   * Cast the given executor IDs to integers and filter out the ones that fail.
   * 将给定executor IDs转换Int列表,如果转换失败抛出异常,所有的执行者ID应该是整数
   * All executors IDs should be integers since we launched these executors. However,
   * the kill interface on the driver side accepts arbitrary strings, so we need to
   * handle non-integer executor IDs just to be safe.
    * 自从我们启动这些执行者以来,所有的执行者ID都应该是整数。
    * 然而,驱动程序端的kill接口接受任意字符串，所以我们需要处理非整数执行器ID才能安全。
   */
  private def formatExecutorIds(executorIds: Seq[String]): Seq[Int] = {
    executorIds.flatMap { executorId =>
      try {
        Some(executorId.toInt)
      } catch {
        case e: NumberFormatException =>
          logError(s"Encountered executor with a non-integer ID: $executorId. Ignoring")
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
          None
      }
    }
  }

  /**
   * Ask the worker on which the specified executor is launched to kill the executor.
   * 要求在指定的worker启动杀死executor
   */
  private def killExecutor(exec: ExecutorDesc): Unit = {
    exec.worker.removeExecutor(exec)
    exec.worker.endpoint.send(KillExecutor(masterUrl, exec.application.id, exec.id))
    exec.state = ExecutorState.KILLED
  }

  /**
   * Rebuild a new SparkUI from the given application's event logs.
   * Return the UI if successful, else None
   * 从给定的应用程序的事件日志重新构建SparkUI
   */
  private[master] def rebuildSparkUI(app: ApplicationInfo): Option[SparkUI] = {
    val appName = app.desc.name
    val notFoundBasePath = HistoryServer.UI_PATH_PREFIX + "/not-found"
    try {
      val eventLogDir = app.desc.eventLogDir
        .getOrElse {
          // Event logging is not enabled for this application
          //此应用程序未启用事件日志记录
          app.desc.appUiUrl = notFoundBasePath
          //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
          return None
        }

      val eventLogFilePrefix = EventLoggingListener.getLogPath(
          eventLogDir, app.id, appAttemptId = None, compressionCodecName = app.desc.eventLogCodec)
      val fs = Utils.getHadoopFileSystem(eventLogDir, hadoopConf)
      val inProgressExists = fs.exists(new Path(eventLogFilePrefix +
          EventLoggingListener.IN_PROGRESS))

      if (inProgressExists) {
        // Event logging is enabled for this application, but the application is still in progress
        //为此应用程序启用事件日志记录,但应用程序仍在进行中
        logWarning(s"Application $appName is still in progress, it may be terminated abnormally.")
      }

      val (eventLogFile, status) = if (inProgressExists) {
        (eventLogFilePrefix + EventLoggingListener.IN_PROGRESS, " (in progress)")
      } else {
        (eventLogFilePrefix, " (completed)")
      }

      val logInput = EventLoggingListener.openEventLog(new Path(eventLogFile), fs)
      val replayBus = new ReplayListenerBus()
      val ui = SparkUI.createHistoryUI(new SparkConf, replayBus, new SecurityManager(conf),
        appName + status, HistoryServer.UI_PATH_PREFIX + s"/${app.id}", app.startTime)
      val maybeTruncated = eventLogFile.endsWith(EventLoggingListener.IN_PROGRESS)
      try {
        replayBus.replay(logInput, eventLogFile, maybeTruncated)
      } finally {
        logInput.close()
      }
      appIdToUI(app.id) = ui
      webUi.attachSparkUI(ui)
      // Application UI is successfully rebuilt, so link the Master UI to it
      //应用程序UI成功重建,因此将主UI连接到它
      app.desc.appUiUrl = ui.basePath
      Some(ui)
    } catch {
      case fnf: FileNotFoundException =>
        // Event logging is enabled for this application, but no event logs are found
        //此应用程序启用了事件日志记录,但没有找到事件日志
        val title = s"Application history not found (${app.id})"
        var msg = s"No event logs found for application $appName in ${app.desc.eventLogDir.get}."
        logWarning(msg)
        msg += " Did you specify the correct logging directory?"
        msg = URLEncoder.encode(msg, "UTF-8")
        app.desc.appUiUrl = notFoundBasePath + s"?msg=$msg&title=$title"
        None
      case e: Exception =>
        // Relay exception message to application UI page
        //中继异常消息到应用程序UI页面
        val title = s"Application history load error (${app.id})"
        val exception = URLEncoder.encode(Utils.exceptionString(e), "UTF-8")
        var msg = s"Exception in replaying log for application $appName!"
        logError(msg, e)
        msg = URLEncoder.encode(msg, "UTF-8")
        app.desc.appUiUrl = notFoundBasePath + s"?msg=$msg&exception=$exception&title=$title"
        None
    }
  }

  /** 
   *  Generate a new app ID given a app's submission date
   *  给一个提交日期 生成一个新的应用程序ID
   *  */
  private def newApplicationId(submitDate: Date): String = {
    //精确到秒 For application IDs
    val appId = "app-%s-%04d".format(createDateFormat.format(submitDate), nextAppNumber) 
    nextAppNumber += 1//自增1
    appId
  }

  /** 
   *  Check for, and remove, any timed-out workers 
   *  
   *如果当前时间减去Worker最近一次的状态更新时间小于定时器的话,就认为该Worker还处于Alive状态
   *否则认为该Worker因为没有发送心跳消息而挂.  
   * 
   * 处理步骤如下:
   * 1)过滤出所有超时的Worker,即使用当前时间减去Worker最大超时时间仍然大于lastHeartbeat的Worker节点
   * 2)如果WorkerInfo的状态是WorkerState.DEAD,则等待足够长的时间后将它从workers列表中移除
   *   足够长的时间的计算公式为:spark.dead.worker.persistence(15)+1乘以Wroker最大超时间conf.getLong("spark.worker.timeout", 60) * 1000
   *   如果worker.state不是WorkerState.DEAD,则调用removeWorker方法将WorkerInfo的状态设置DEAD,从idToWorker缓存中移除Worker的Id,
   *   从addressToWorker的缓存中移除WorkerInfo,最后向此WorkerInfo的所有Executor所有服务的Drver application发送ExecutorUpdated消息
   *   更新Executor的状态为Lost,最后使用removeDriver重新调度之前调度给此Worker的Driver
   **/
  
  private def timeOutDeadWorkers() {
    // Copy the workers into an array so we don't modify the hashset while iterating through it
    val currentTime = System.currentTimeMillis()
    //workers过滤出所有当前时间减去超时定时器间隔时间小于Worker最近一次的状态更新时间
    val toRemove = workers.filter(_.lastHeartbeat < currentTime - WORKER_TIMEOUT_MS).toArray
    for (worker <- toRemove) {
      if (worker.state != WorkerState.DEAD) {
        logWarning("Removing %s because we got no heartbeat in %d seconds".format(
          worker.id, WORKER_TIMEOUT_MS / 1000))
          //如果判定结果认为相应的Worder已经不再存活,那么利用removeWorker函数通知Driver Application
        removeWorker(worker)
      } else {
        //如果WorkerInfo的状态是WorkerState.DEAD,则等待足够长的时间后将它从workers列表中移除
        // 足够长的时间的计算公式为:spark.dead.worker.persistence(15)+1乘以Wroker最大超时间
        //conf.getLong("spark.worker.timeout", 60) * 1000
        if (worker.lastHeartbeat < currentTime - ((REAPER_ITERATIONS + 1) * WORKER_TIMEOUT_MS)) {
          workers -= worker // we've seen this DEAD worker in the UI, etc. for long enough; cull it
        }
      }
    }
  }

  private def newDriverId(submitDate: Date): String = {
    val appId = "driver-%s-%04d".format(createDateFormat.format(submitDate), nextDriverNumber)
    nextDriverNumber += 1
    appId
  }
/**
 * 创建DriverInfo
 */
  private def createDriver(desc: DriverDescription): DriverInfo = {
    val now = System.currentTimeMillis()
    val date = new Date(now)
    new DriverInfo(now, newDriverId(date), desc, date)
  }
/**
 * 向Worker发送LaunchDriver消息
 */
  private def launchDriver(worker: WorkerInfo, driver: DriverInfo) {
    logInfo("Launching driver " + driver.id + " on worker " + worker.id)
    worker.addDriver(driver)
    //将worker加入到driver内部缓存结构中
    driver.worker = Some(worker)
    //Master向worker发送LaunchDriver消息,Worker启动driver
    worker.endpoint.send(LaunchDriver(driver.id, driver.desc))
    //driver的state就编程RUNNING
    driver.state = DriverState.RUNNING
  }

  //移除driver
  private def removeDriver(
      driverId: String,
      finalState: DriverState,
      exception: Option[Exception]) {
    //用find高阶函数找到driverid对应的driver
    drivers.find(d => d.id == driverId) match {
        //如果找到了，some，样例类（Option）
      case Some(driver) =>
        logInfo(s"Removing driver: $driverId")
        //将driver从内存缓存中清除
        drivers -= driver
        //如果已完成的driver大小大于等于retainedDrivers值，进行trimstart
        if (completedDrivers.size >= RETAINED_DRIVERS) {
          val toRemove = math.max(RETAINED_DRIVERS / 10, 1)
          completedDrivers.trimStart(toRemove)
        }
        //向completedDrivers加入driver
        completedDrivers += driver
        //使用持久化引擎删除driver
        persistenceEngine.removeDriver(driver)

        //设置driver的state  exception
        driver.state = finalState
        driver.exception = exception
        //将driver所在的worker，移除driver
        driver.worker.foreach(w => w.removeDriver(driver))
        //重新调度
        schedule()
      case None =>
        logWarning(s"Asked to remove unknown driver: $driverId")
    }
  }
}

private[deploy] object Master extends Logging {
  val SYSTEM_NAME = "sparkMaster"
  val ENDPOINT_NAME = "Master"
   //master启动的入口
  def main(argStrings: Array[String]) {
    SignalLogger.register(log)
    val conf = new SparkConf
    //配制文件读取参数
    val args = new MasterArguments(argStrings, conf)
    //创建Actor
    val (rpcEnv, _, _) = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, conf)
    //等待结束
    rpcEnv.awaitTermination()
  }

  /**
   * Rpc Environment(RpcEnv)是一个RpcEndpoints用于处理消息的环境,
   * 它管理着整个RpcEndpoints的声明周期：(1)根据name或uri注册endpoints(2)管理各种消息的处理(3)停止endpoints。
   * RpcEnv必须通过工厂类RpcEnvFactory创建
   * Start the Master and return a three tuple of:
    * 启动Master并返回三个元组：
   *   (1) The Master RpcEnv
   *   (2) The web UI bound port
   *   (3) The REST server bound port, if any
   */
  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      conf: SparkConf): (RpcEnv, Int, Option[Int]) = {
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, conf, securityMgr)
    //根据RpcEndpoint的name注册到RpcEnv中并返回它的一个引用RpcEndpointRef
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME,
      new Master(rpcEnv, rpcEnv.address, webUiPort, securityMgr, conf))
      //该方法阻塞操作
    val portsResponse = masterEndpoint.askWithRetry[BoundPortsResponse](BoundPortsRequest)
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort)
  }
   
  
}
