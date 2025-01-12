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
package org.apache.spark.status.api.v1

import java.util.{Arrays, Date, List => JList}
import javax.ws.rs.{DefaultValue, GET, Produces, QueryParam}
import javax.ws.rs.core.MediaType

import org.apache.spark.deploy.history.ApplicationHistoryInfo
import org.apache.spark.deploy.master.{ApplicationInfo => InternalApplicationInfo}

@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class ApplicationListResource(uiRoot: UIRoot) {

  @GET
  def appList(
      @QueryParam("status") status: JList[ApplicationStatus],
      @DefaultValue("2010-01-01") @QueryParam("minDate") minDate: SimpleDateParam,
      @DefaultValue("3000-01-01") @QueryParam("maxDate") maxDate: SimpleDateParam)
  : Iterator[ApplicationInfo] = {
    val allApps = uiRoot.getApplicationInfoList
    val adjStatus = {
      if (status.isEmpty) {
        //可变参数时不能直接传入Range或集合或数组对象,需要使用:_*转换才可传入
        Arrays.asList(ApplicationStatus.values(): _*)
      } else {
        status
      }
    }
    val includeCompleted = adjStatus.contains(ApplicationStatus.COMPLETED)
    val includeRunning = adjStatus.contains(ApplicationStatus.RUNNING)
    allApps.filter { app =>
      val anyRunning = app.attempts.exists(!_.completed)
      // if any attempt is still running, we consider the app to also still be running
      //如果有任何尝试仍在运行，我们认为该应用仍然在运行
      val statusOk = (!anyRunning && includeCompleted) ||
        (anyRunning && includeRunning)
      // keep the app if *any* attempts fall in the right time window
      //如果*任何*尝试落在正确的时间窗口，请保留应用程序
      val dateOk = app.attempts.exists { attempt =>
        attempt.startTime.getTime >= minDate.timestamp &&
          attempt.startTime.getTime <= maxDate.timestamp
      }
      statusOk && dateOk
    }
  }
}

private[spark] object ApplicationsListResource {
  def appHistoryInfoToPublicAppInfo(app: ApplicationHistoryInfo): ApplicationInfo = {
    new ApplicationInfo(
      id = app.id,
      name = app.name,
      attempts = app.attempts.map { internalAttemptInfo =>
        new ApplicationAttemptInfo(
          attemptId = internalAttemptInfo.attemptId,
          startTime = new Date(internalAttemptInfo.startTime),
          endTime = new Date(internalAttemptInfo.endTime),
          sparkUser = internalAttemptInfo.sparkUser,
          completed = internalAttemptInfo.completed
        )
      }
    )
  }

  def convertApplicationInfo(
      internal: InternalApplicationInfo,
      completed: Boolean): ApplicationInfo = {
    // standalone application info always has just one attempt
    //独立的应用信息总是只有一个尝试
    new ApplicationInfo(
      id = internal.id,
      name = internal.desc.name,
      attempts = Seq(new ApplicationAttemptInfo(
        attemptId = None,
        startTime = new Date(internal.startTime),
        endTime = new Date(internal.endTime),
        sparkUser = internal.desc.user,
        completed = completed
      ))
    )
  }

}
