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

package org.apache.spark.sql.execution.datasources.jdbc

import java.sql.{Driver, DriverManager}

import scala.collection.mutable

import org.apache.spark.Logging
import org.apache.spark.util.Utils

/**
 * java.sql.DriverManager is always loaded by bootstrap classloader,
 * so it can't load JDBC drivers accessible by Spark ClassLoader.
  * java.sql.DriverManager始终由引导程序类加载器加载,
  * 因此无法加载Spark ClassLoader可访问的JDBC驱动程序
 *
 * To solve the problem, drivers from user-supplied jars are wrapped into thin wrapper.
  * 为了解决这个问题,来自用户提供的罐子的驱动程序被包装成薄包装
 */
object DriverRegistry extends Logging {

  private val wrapperMap: mutable.Map[String, DriverWrapper] = mutable.Map.empty

  def register(className: String): Unit = {
    val cls = Utils.getContextOrSparkClassLoader.loadClass(className)
    if (cls.getClassLoader == null) {
      logTrace(s"$className has been loaded with bootstrap ClassLoader, wrapper is not required")
    } else if (wrapperMap.get(className).isDefined) {
      logTrace(s"Wrapper for $className already exists")
    } else {
      synchronized {
        if (wrapperMap.get(className).isEmpty) {
          val wrapper = new DriverWrapper(cls.newInstance().asInstanceOf[Driver])
          DriverManager.registerDriver(wrapper)
          wrapperMap(className) = wrapper
          logTrace(s"Wrapper for $className registered")
        }
      }
    }
  }

  def getDriverClassName(url: String): String = DriverManager.getDriver(url) match {
    case wrapper: DriverWrapper => wrapper.wrapped.getClass.getCanonicalName
    case driver => driver.getClass.getCanonicalName
  }
}

