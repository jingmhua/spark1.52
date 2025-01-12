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

package org.apache.spark.deploy.rest

import java.io.{DataOutputStream, FileNotFoundException}
import java.net.{ConnectException, HttpURLConnection, SocketException, URL}
import javax.servlet.http.HttpServletResponse

import scala.collection.mutable
import scala.io.Source

import com.fasterxml.jackson.core.JsonProcessingException
import com.google.common.base.Charsets

import org.apache.spark.{Logging, SparkConf, SPARK_VERSION => sparkVersion}
import org.apache.spark.util.Utils

/**
 * A client that submits applications to a [[RestSubmissionServer]].
 * 客户提交应用程序到一个restsubmissionserver
 * In protocol version v1, the REST URL takes the form http://[host:port]/v1/submissions/[action],
 * 在协议版本V1,REST的URL以http://[host:port]/v1/submissions/[action]的形式
 * where [action] can be one of create, kill, or status. Each type of request is represented in
 * 这[action]可以一个创建,杀死,或者状态,每种类型的请求的HTTP消息发送到下列前缀代表
 * an HTTP message sent to the following prefixes: 	
 *   (1) submit - POST to /submissions/create
 *       提交---POST  /submissions/create
 *   (2) kill - POST /submissions/kill/[submissionId]
 *   		 杀死--POST  /submissions/kill/[submissionId]
 *   (3) status - GET /submissions/status/[submissionId]
 *       状态--GET /submissions/status/[submissionId]
 *
 * In the case of (1), parameters are posted in the HTTP body in the form of JSON fields.
 * 情况1,在HTTP以JSON字段的形式参数提交,否则,客户端以网址形式指定参数
 * Otherwise, the URL fully specifies the intended action of the client.
 *
 * Since the protocol is expected to be stable across Spark versions, existing fields cannot be
 * 由于该协议预计将稳定在Spark版本,无法添加或删除现有字段,
 * added or removed, though new optional fields can be added. In the rare event that forward or
 * 虽然可以添加新的可选字段,在向前或向后兼容性被打破,Spark必须引入一个新的协议版本
 * backward compatibility is broken, Spark must introduce a new protocol version (e.g. v2).
 *
 * The client and the server must communicate using the same version of the protocol. If there
 * 客户端和服务器必须使用相同版本的协议进行通信,
 * is a mismatch, the server will respond with the highest protocol version it supports. A future
 * 如果有一个不匹配,它支持服务器将响应最高的协议版本,
 * implementation of this client can use that information to retry using the version specified
 * 该客户端的未来实现可以使用该信息来重试使用服务器指定的版本
 * by the server.
 */
private[spark] class RestSubmissionClient(master: String) extends Logging {
  import RestSubmissionClient._

  private val supportedMasterPrefixes = Seq("spark://", "mesos://")

  private val masters: Array[String] = if (master.startsWith("spark://")) {
    Utils.parseStandaloneMasterUrls(master)
  } else {
    Array(master)
  }

  // Set of masters that lost contact with us, used to keep track of
  //失去了与我们的联系的masters,用于跟踪通讯主节点是否还活着
  // whether there are masters still alive for us to communicate with
  private val lostMasters = new mutable.HashSet[String]

  /**
   * Submit an application specified by the parameters in the provided request.
   * 提交所提供的请求中的参数指定的应用程序
   * If the submission was successful, poll the status of the submission and report
   * 如果提交成功,轮询提交的状态并将其报告给用户
   * it to the user. Otherwise, report the error message provided by the server.
   * 否则,报告服务器提供的错误信息
   */
  def createSubmission(request: CreateSubmissionRequest): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request to launch an application in $master.")
    var handled: Boolean = false
    var response: SubmitRestProtocolResponse = null
    for (m <- masters if !handled) {
      validateMaster(m)
      val url = getSubmitUrl(m)
      try {
        response = postJson(url, request.toJson)
        response match {
          case s: CreateSubmissionResponse =>
            if (s.success) {
              reportSubmissionStatus(s)
              handleRestResponse(s)
              handled = true
            }
          case unexpected =>
            handleUnexpectedRestResponse(unexpected)
        }
      } catch {
        case e: SubmitRestConnectionException =>
          if (handleConnectionException(m)) {
            throw new SubmitRestConnectionException("Unable to connect to server", e)
          }
      }
    }
    response
  }

  /** 
   *  Request that the server kill the specified submission.
   *  请求服务器杀死指定的提交 
   *  */
  def killSubmission(submissionId: String): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request to kill submission $submissionId in $master.")
    var handled: Boolean = false
    var response: SubmitRestProtocolResponse = null
    for (m <- masters if !handled) {
      validateMaster(m)
      val url = getKillUrl(m, submissionId)
      try {
        response = post(url)
        response match {
          case k: KillSubmissionResponse =>
            if (!Utils.responseFromBackup(k.message)) {
              handleRestResponse(k)
              handled = true
            }
          case unexpected =>
            handleUnexpectedRestResponse(unexpected)
        }
      } catch {
        case e: SubmitRestConnectionException =>
          if (handleConnectionException(m)) {
            throw new SubmitRestConnectionException("Unable to connect to server", e)
          }
      }
    }
    response
  }

  /** 
   *  Request the status of a submission from the server. 
   *  请求从服务器提交的状态
   *  */
  def requestSubmissionStatus(
      submissionId: String,
      quiet: Boolean = false): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request for the status of submission $submissionId in $master.")

    var handled: Boolean = false
    var response: SubmitRestProtocolResponse = null
    for (m <- masters if !handled) {
      validateMaster(m)
      val url = getStatusUrl(m, submissionId)
      try {
        response = get(url)
        response match {
          case s: SubmissionStatusResponse if s.success =>
            if (!quiet) {
              handleRestResponse(s)
            }
            handled = true
          case unexpected =>
            handleUnexpectedRestResponse(unexpected)
        }
      } catch {
        case e: SubmitRestConnectionException =>
          if (handleConnectionException(m)) {
            throw new SubmitRestConnectionException("Unable to connect to server", e)
          }
      }
    }
    response
  }

  /** 
   *  Construct a message that captures the specified parameters for submitting an application. 
   *  构建一个用于捕获提交应用程序的指定参数的消息
   *  */
  def constructSubmitRequest(
      appResource: String,
      mainClass: String,
      appArgs: Array[String],
      sparkProperties: Map[String, String],
      environmentVariables: Map[String, String]): CreateSubmissionRequest = {
    val message = new CreateSubmissionRequest
    message.clientSparkVersion = sparkVersion
    message.appResource = appResource
    message.mainClass = mainClass
    message.appArgs = appArgs
    message.sparkProperties = sparkProperties
    message.environmentVariables = environmentVariables
    message.validate()
    message
  }

  /** 
   *  Send a GET request to the specified URL. 
   *  发送一个get请求到指定的地址
   *  */
  private def get(url: URL): SubmitRestProtocolResponse = {
    logDebug(s"Sending GET request to server at $url.")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    readResponse(conn)
  }

  /** 
   *  Send a POST request to the specified URL. 
   *  发送一个POST请求到指定的地址
   *  */
  private def post(url: URL): SubmitRestProtocolResponse = {
    logDebug(s"Sending POST request to server at $url.")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    readResponse(conn)
  }

  /** 
   *  Send a POST request with the given JSON as the body to the specified URL. 
   *  发送一个JSON格式POSt请求到指定的地址
   *  */
  private def postJson(url: URL, json: String): SubmitRestProtocolResponse = {
    logDebug(s"Sending POST request to server at $url:\n$json")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("charset", "utf-8")
    conn.setDoOutput(true)
    try {
      val out = new DataOutputStream(conn.getOutputStream)
      Utils.tryWithSafeFinally {
        out.write(json.getBytes(Charsets.UTF_8))
      } {
        out.close()
      }
    } catch {
      case e: ConnectException =>
        throw new SubmitRestConnectionException("Connect Exception when connect to server", e)
    }
    readResponse(conn)
  }

  /**
   * Read the response from the server and return it as a validated [[SubmitRestProtocolResponse]].
   * 读取服务器的响应,并将其作为验证返回
   * If the response represents an error, report the embedded message to the user.
   * 如果响应表示错误,将嵌入的消息报告给用户
   * Exposed for testing.
   */
  private[rest] def readResponse(connection: HttpURLConnection): SubmitRestProtocolResponse = {
    try {
      val dataStream =
        if (connection.getResponseCode == HttpServletResponse.SC_OK) {
          connection.getInputStream
        } else {
          connection.getErrorStream
        }
      // If the server threw an exception while writing a response, it will not have a body
      //如果服务器在写响应时抛出异常,
      if (dataStream == null) {
        throw new SubmitRestProtocolException("Server returned empty body")
      }
      val responseJson = Source.fromInputStream(dataStream).mkString
      logDebug(s"Response from the server:\n$responseJson")
      val response = SubmitRestProtocolMessage.fromJson(responseJson)
      response.validate()
      response match {
        // If the response is an error, log the message
        //如果响应是一个错误,日志消息
        case error: ErrorResponse =>
          logError(s"Server responded with error:\n${error.message}")
          error
        // Otherwise, simply return the response
        //否则,简单地返回响应
        case response: SubmitRestProtocolResponse => response
        case unexpected =>
          throw new SubmitRestProtocolException(
            s"Message received from server was not a response:\n${unexpected.toJson}")
      }
    } catch {
      case unreachable @ (_: FileNotFoundException | _: SocketException) =>
        throw new SubmitRestConnectionException("Unable to connect to server", unreachable)
      case malformed @ (_: JsonProcessingException | _: SubmitRestProtocolException) =>
        throw new SubmitRestProtocolException("Malformed response received from server", malformed)
    }
  }

  /** 
   *  Return the REST URL for creating a new submission. 
   *  返回创建新提交的网址(URL)
   *  */
  private def getSubmitUrl(master: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/create")
  }

  /** 
   *  Return the REST URL for killing an existing submission. 
   *  返回杀死现有提交的地址
   *  */
  private def getKillUrl(master: String, submissionId: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/kill/$submissionId")
  }

  /** 
   *  Return the REST URL for requesting the status of an existing submission. 
   *  返回请求存在提交的状态的网址(URL)
   *  */
  private def getStatusUrl(master: String, submissionId: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/status/$submissionId")
  }

  /** 
   *  Return the base URL for communicating with the server, including the protocol version. 
   *  返回与服务器通信的基本网址(RUL),包括协议版本
   *  */
  private def getBaseUrl(master: String): String = {
    var masterUrl = master
    supportedMasterPrefixes.foreach { prefix =>
      if (master.startsWith(prefix)) {
        masterUrl = master.stripPrefix(prefix)
      }
    }
    //stripSuffix去掉<string>字串中结尾的字符
    masterUrl = masterUrl.stripSuffix("/")
    s"http://$masterUrl/$PROTOCOL_VERSION/submissions"
  }

  /** 
   *  Throw an exception if this is not standalone mode.
   *  抛出一个异常,如果这不是独立的模式
   *   */
  private def validateMaster(master: String): Unit = {
    val valid = supportedMasterPrefixes.exists { prefix => master.startsWith(prefix) }
    if (!valid) {
      throw new IllegalArgumentException(
        "This REST client only supports master URLs that start with " +
          "one of the following: " + supportedMasterPrefixes.mkString(","))
    }
  }

  /** 
   *  Report the status of a newly created submission. 
   *  报告一个新创建的提交的状态
   *  */
  private def reportSubmissionStatus(
      submitResponse: CreateSubmissionResponse): Unit = {
    if (submitResponse.success) {
      val submissionId = submitResponse.submissionId
      if (submissionId != null) {
        logInfo(s"Submission successfully created as $submissionId. Polling submission state...")
        pollSubmissionStatus(submissionId)
      } else {
        // should never happen 不应该发生
        logError("Application successfully submitted, but submission ID was not provided!")
      }
    } else {
      val failMessage = Option(submitResponse.message).map { ": " + _ }.getOrElse("")
      logError(s"Application submission failed$failMessage")
    }
  }

  /**
   * Poll the status of the specified submission and log it.
   * 轮询指定的提交的状态并将其记录,
   * This retries up to a fixed number of times before giving up.
   * 在放弃之前重试到固定次数
   */
  private def pollSubmissionStatus(submissionId: String): Unit = {
    (1 to REPORT_DRIVER_STATUS_MAX_TRIES).foreach { _ =>
      val response = requestSubmissionStatus(submissionId, quiet = true)
      val statusResponse = response match {
        case s: SubmissionStatusResponse => s
        //意想不到的类型,让上游的调用方处理它
        case _ => return // unexpected type, let upstream caller handle it
      }
      if (statusResponse.success) {
        val driverState = Option(statusResponse.driverState)
        val workerId = Option(statusResponse.workerId)
        val workerHostPort = Option(statusResponse.workerHostPort)
        val exception = Option(statusResponse.message)
        // Log driver state, if present 日志驱动状态,如果存在
        driverState match {
          case Some(state) => logInfo(s"State of driver $submissionId is now $state.")
          case _ => logError(s"State of driver $submissionId was not found!")
        }
        // Log worker node, if present 日志工作节点,如果存在
        (workerId, workerHostPort) match {
          case (Some(id), Some(hp)) => logInfo(s"Driver is running on worker $id at $hp.")
          case _ =>
        }
        // Log exception stack trace, if present
        //日志异常堆栈跟踪,如果存在
        exception.foreach { e => logError(e) }
        return
      }
      Thread.sleep(REPORT_DRIVER_STATUS_INTERVAL)
    }
    logError(s"Error: Master did not recognize driver $submissionId.")
  }

  /** 
   *  Log the response sent by the server in the REST application submission protocol. 
   *  日志服务器在其他应用程序提交协议中发送的响应
   *  */
  private def handleRestResponse(response: SubmitRestProtocolResponse): Unit = {
    logInfo(s"Server responded with ${response.messageType}:\n${response.toJson}")
  }

  /** 
   *  Log an appropriate error if the response sent by the server is not of the expected type. 
   *  如果服务器发送的响应不是预期的类型,则记录一个适当的错误
   *  */
  private def handleUnexpectedRestResponse(unexpected: SubmitRestProtocolResponse): Unit = {
    logError(s"Error: Server responded with message of unexpected type ${unexpected.messageType}.")
  }

  /**
   * When a connection exception is caught, return true if all masters are lost.
   * 当捕获连接异常,如果所有的主节点都丢失,返回真
   * Note that the heuristic used here does not take into account that masters
   * can recover during the lifetime of this client. This assumption should be
   * harmless because this client currently does not support retrying submission
   * on failure yet (SPARK-6443).
   */
  private def handleConnectionException(masterUrl: String): Boolean = {
    if (!lostMasters.contains(masterUrl)) {
      logWarning(s"Unable to connect to server ${masterUrl}.")
      lostMasters += masterUrl
    }
    lostMasters.size >= masters.size
  }
}

private[spark] object RestSubmissionClient {
  private val REPORT_DRIVER_STATUS_INTERVAL = 1000
  private val REPORT_DRIVER_STATUS_MAX_TRIES = 10
  val PROTOCOL_VERSION = "v1"

  /**
   * Submit an application, assuming Spark parameters are specified through the given config.
   * 提交申请,假设Spark参数通过指定的配置,测试目的抽象到自己的方法
   * This is abstracted to its own method for testing purposes.
   */
  def run(
      appResource: String,
      mainClass: String,
      appArgs: Array[String],
      conf: SparkConf,
      //System.getenv()和System.getProperties()的区别
      //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
      //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
      env: Map[String, String] = sys.env): SubmitRestProtocolResponse = {
    val master = conf.getOption("spark.master").getOrElse {
      throw new IllegalArgumentException("'spark.master' must be set.")
    }
    val sparkProperties = conf.getAll.toMap
    val environmentVariables = env.filter { case (k, _) => k.startsWith("SPARK_") }
    val client = new RestSubmissionClient(master)
    //构建request
    val submitRequest = client.constructSubmitRequest(
      appResource, mainClass, appArgs, sparkProperties, environmentVariables)
    client.createSubmission(submitRequest)
  }

  //main方法由sparksubmit类的runMain方法，通过反射机制调用的
  def main(args: Array[String]): Unit = {
    if (args.size < 2) {
      sys.error("Usage: RestSubmissionClient [app resource] [main class] [app args*]")
      sys.exit(1)
    }
    val appResource = args(0)
    val mainClass = args(1)
    val appArgs = args.slice(2, args.size)// 参数顺序是 args.primaryResource（用户jar），args.mainClass,args.childArgs
    val conf = new SparkConf
    run(appResource, mainClass, appArgs, conf)
  }
}
