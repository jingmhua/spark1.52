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

package org.apache.spark.deploy.worker

import java.io.{File, FileOutputStream, InputStream, IOException}
import java.lang.System._

import scala.collection.JavaConversions._
import scala.collection.Map

import org.apache.spark.Logging
import org.apache.spark.SecurityManager
import org.apache.spark.deploy.Command
import org.apache.spark.launcher.WorkerCommandBuilder
import org.apache.spark.util.Utils

/**
 * Utilities for running commands with the spark classpath.
 * 运行命令行工具在Spark路径
 */
private[deploy]
object CommandUtils extends Logging {

  /**
   * 基于给定的参数创建ProcessBuilder
   * Build a ProcessBuilder based on the given parameters.
   * The `env` argument is exposed for testing.
    * `env`参数暴露出来进行测试
    * 为了扩展构建本地command，就调用的时候缺省了2个参数， 一个是本地系统环境变量， 一个是本地classpath
   */
  def buildProcessBuilder(
      command: Command,
      securityMgr: SecurityManager,
      memory: Int,
      sparkHome: String,
      substituteArguments: String => String,
      classPaths: Seq[String] = Seq[String](),
      //System.getenv()和System.getProperties()的区别
      //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
      //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
      env: Map[String, String] = sys.env): ProcessBuilder = {
    //ProcessBuilder实例管理一个进程属性集
    //结合command要运行的环境，重新构建一个命令，加入本地环境变量， 系统classpath，替换掉传过来的占位符
    val localCommand = buildLocalCommand(
      command, securityMgr, substituteArguments, classPaths, env)
    //结合上部构建的localcommand构建一个结合本地环境的commonseq，然后用他去构建一个processbuilder
    val commandSeq = buildCommandSeq(localCommand, memory, sparkHome)//driver，executor的运行时内存
    //ProcessBuilder此类用于创建操作系统进程,它提供一种启动和管理进程（也就是应用程序）的方法
    val builder = new ProcessBuilder(commandSeq: _*)
    val environment = builder.environment()
    for ((key, value) <- localCommand.environment) {
      environment.put(key, value)
    }
    builder
  }

  /**
    * 用于构建命令行参数序列
    * @param command
    * @param memory
    * @param sparkHome
    * @return
    */
  private def buildCommandSeq(command: Command, memory: Int, sparkHome: String): Seq[String] = {
    // SPARK-698: do not call the run.cmd script, as process.destroy()
    // fails to kill a process tree on Windows
    //无法在Windows上杀死进程树
    val cmd = new WorkerCommandBuilder(sparkHome, memory, command).buildCommand()
    cmd.toSeq ++ Seq(command.mainClass) ++ command.arguments
  }

  /**
    *通过复制ApplicationDescription中的类路径,包路径,环境变量,Java选项参数等信息,在本地创建Command
   * Build a command based on the given one, taking into account the local environment
   * of where this command is expected to run, substitute any placeholders, and append
   * any extra class paths.
   */
  private def buildLocalCommand(
      command: Command,
      securityMgr: SecurityManager,
      substituteArguments: String => String,
      classPath: Seq[String] = Seq[String](),
      env: Map[String, String]): Command = {
    val libraryPathName = Utils.libraryPathEnvName//返回系统的path，也就是一些
    val libraryPathEntries = command.libraryPathEntries
    val cmdLibraryPath = command.environment.get(libraryPathName)

    var newEnvironment = if (libraryPathEntries.nonEmpty && libraryPathName.nonEmpty) {
      val libraryPaths = libraryPathEntries ++ cmdLibraryPath ++ env.get(libraryPathName)
      command.environment + ((libraryPathName, libraryPaths.mkString(File.pathSeparator)))
    } else {
      command.environment//restsubmissionclient发送过来的环境变量只有spark_和mesos_开头的环境变量
      //也即是对于driver端system.getenv()系统环境变量获取的值， 如spark-env.sh初始化SPARK_开头的环境变量， 在提交的时候已经创建好了。
    }

    // set auth secret to env variable if needed
    //设置环境变量如果需要认证的密码
    if (securityMgr.isAuthenticationEnabled) {
      newEnvironment += (SecurityManager.ENV_AUTH_SECRET -> securityMgr.getSecretKey)
    }

    Command(
      //对于driver并不是用户命令的入库而是一个封装类org.apache.spark.deploy.worker.DriverWrapper，在封装类里面进行了进一步解析。
      //对于executor是这个 org.apache.executor.CoarseGranedExecutorsBackend类
      command.mainClass,
      command.arguments.map(substituteArguments),
      newEnvironment,
      command.classPathEntries ++ classPath,
      //已捕获环境变量中的库路径
      Seq[String](), // library path already captured in environment variable
      // filter out auth secret from java options
      //已经在环境变量中捕获的库路径从java选项过滤掉auth secret
      command.javaOpts.filterNot(_.startsWith("-D" + SecurityManager.SPARK_AUTH_SECRET_CONF)))//spark.jars 在此处
  }

  /** 
   *  Spawn a thread that will redirect a given stream to a file 
   *  生成一个线程,将一个给定的流重定向到一个文件
   *  */
  def redirectStream(in: InputStream, file: File) {
    val out = new FileOutputStream(file, true)
    // TODO: It would be nice to add a shutdown hook here that explains why the output is
    //       terminating. Otherwise if the worker dies the executor logs will silently stop.
    //在这里添加一个关闭挂钩是很好的,它解释了为什么输出结束。否则如果工作节点死亡,执行者日志将静默停止。
    new Thread("redirect output to " + file) {
      override def run() {
        try {
          Utils.copyStream(in, out, true)
        } catch {
          case e: IOException =>
            logInfo("Redirection to " + file + " closed: " + e.getMessage)
        }
      }
    }.start()
  }
}
