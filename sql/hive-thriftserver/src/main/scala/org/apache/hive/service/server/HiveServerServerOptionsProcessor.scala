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
package org.apache.hive.service.server

import org.apache.hive.service.server.HiveServer2.{StartOptionExecutor, ServerOptionsProcessor}

/**
 * Class to upgrade a package-private class to public, and
 * implement a `process()` operation consistent with
 * the behavior of older Hive versions
  * 用于将package-private类升级为public的类,并实现与旧Hive版本的行为一致的`process（）`操作
 * @param serverName name of the hive server
 */
private[apache] class HiveServerServerOptionsProcessor(serverName: String)
    extends ServerOptionsProcessor(serverName) {

  def process(args: Array[String]): Boolean = {
    // A parse failure automatically triggers a system exit
    //解析失败会自动触发系统退出
    val response = super.parse(args)
    val executor = response.getServerOptionsExecutor()
    // return true if the parsed option was to start the service
    //如果解析的选项是启动服务,则返回true
    executor.isInstanceOf[StartOptionExecutor]
  }
}
