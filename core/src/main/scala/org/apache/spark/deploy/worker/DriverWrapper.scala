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

import java.io.File

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.rpc.RpcEnv
import org.apache.spark.util.{ChildFirstURLClassLoader, MutableURLClassLoader, Utils}

/**
 * Utility object for launching driver programs such that they share fate with the Worker process.
 * 用于启动程序,使得它与Worker进程共享内存,这是使用独立的集群模式
 * This is used in standalone cluster mode only.
 */
object DriverWrapper {
  def main(args: Array[String]) {
    args.toList match {
      /*
       * IMPORTANT: Spark 1.3 provides a stable application submission gateway that is both
       * backward and forward compatible across future Spark versions. Because this gateway
       * 提供一个稳定的应用程序提交网关,这两个网关都是向后和向前兼容整个未来的Spark版本
       * uses this class to launch the driver, the ordering and semantics of the arguments
       * 因为这个网关使用这个类来启动驱动程序,这里的参数的顺序和语义也必须保持版本一致
       * here must also remain consistent across versions.
       */
      case workerUrl :: userJar :: mainClass :: extraArgs =>
        val conf = new SparkConf()
        val rpcEnv = RpcEnv.create("Driver",
          Utils.localHostName(), 0, conf, new SecurityManager(conf))
        rpcEnv.setupEndpoint("workerWatcher", new WorkerWatcher(rpcEnv, workerUrl))
        //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
        val currentLoader = Thread.currentThread.getContextClassLoader
        val userJarUrl = new File(userJar).toURI().toURL()
        val loader =
          if (sys.props.getOrElse("spark.driver.userClassPathFirst", "false").toBoolean) {
            new ChildFirstURLClassLoader(Array(userJarUrl), currentLoader)
          } else {
            new MutableURLClassLoader(Array(userJarUrl), currentLoader)
          }
        //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
        Thread.currentThread.setContextClassLoader(loader)

        // Delegate to supplied main class
        //提供给主类的委托
        val clazz = Utils.classForName(mainClass)
        val mainMethod = clazz.getMethod("main", classOf[Array[String]])
        //下面就开始执行用户的程序了
        mainMethod.invoke(null, extraArgs.toArray[String])

        rpcEnv.shutdown()

      case _ =>
        // scalastyle:off println
        System.err.println("Usage: DriverWrapper <workerUrl> <userJar> <driverMainClass> [options]")
        // scalastyle:on println
        System.exit(-1)
    }
  }
}
