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

package org.apache.spark.streaming

import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.storage.StorageLevel
/**
 * 窗口操作
 */
class WindowOperationsSuite extends TestSuiteBase {
  //窗口测试需要更长的时间
  //最大等待时间的信息
  override def maxWaitTimeMillis: Int = 20000  // large window tests can sometimes take longer
  //确保在这个类中是可见,间隔时间1秒
  override def batchDuration: Duration = Seconds(1)  // making sure its visible in this class
  //较大的滑动输入
  val largerSlideInput = Seq(
    Seq(("a", 1)),
    Seq(("a", 2)),  // 1st window from here,这里的第一个窗口
    Seq(("a", 3)),
    Seq(("a", 4)),  // 2nd window from here 这里的第二个窗口
    Seq(("a", 5)),
    Seq(("a", 6)),  // 3rd window from here 这里的第三个窗口
    Seq(), 
    Seq()           // 4th window from here 这里的第四个窗口
  )
  //较大的滑动
  val largerSlideReduceOutput = Seq(
    Seq(("a", 3)),
    Seq(("a", 10)),
    Seq(("a", 18)),
    Seq(("a", 11))
  )

//大的输入
  val bigInput = Seq(
    Seq(("a", 1)),
    Seq(("a", 1), ("b", 1)),
    Seq(("a", 1), ("b", 1), ("c", 1)),
    Seq(("a", 1), ("b", 1)),
    Seq(("a", 1)),
    Seq(),
    Seq(("a", 1)),
    Seq(("a", 1), ("b", 1)),
    Seq(("a", 1), ("b", 1), ("c", 1)),
    Seq(("a", 1), ("b", 1)),
    Seq(("a", 1)),
    Seq()
  )
//大分组输出
  val bigGroupByOutput = Seq(
    Seq(("a", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1, 1)), ("c", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1, 1)), ("c", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1))),
    Seq(("a", Seq(1))),
    Seq(("a", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1, 1)), ("c", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1, 1)), ("c", Seq(1))),
    Seq(("a", Seq(1, 1)), ("b", Seq(1))),
    Seq(("a", Seq(1)))
  )

//大的计算输出
  val bigReduceOutput = Seq(
    Seq(("a", 1)),
    Seq(("a", 2), ("b", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 1)),
    Seq(("a", 1)),
    Seq(("a", 1)),
    Seq(("a", 2), ("b", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 1)),
    Seq(("a", 1))
  )

  /*
  The output of the reduceByKeyAndWindow with inverse function but without a filter
   
  function will be different from the naive reduceByKeyAndWindow, as no keys get
  
  eliminated from the ReducedWindowedDStream even if the value of a key becomes 0.
  */

  val bigReduceInvOutput = Seq(
    Seq(("a", 1)),
    Seq(("a", 2), ("b", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 1), ("c", 0)),
    Seq(("a", 1), ("b", 0), ("c", 0)),
    Seq(("a", 1), ("b", 0), ("c", 0)),
    Seq(("a", 2), ("b", 1), ("c", 0)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 2), ("c", 1)),
    Seq(("a", 2), ("b", 1), ("c", 0)),
    Seq(("a", 1), ("b", 0), ("c", 0))
  )

  // Testing window operation
  //测试窗口操作
  testWindow(
    "basic window",
    Seq( Seq(0), Seq(1), Seq(2), Seq(3), Seq(4), Seq(5)),
    Seq( Seq(0), Seq(0, 1), Seq(1, 2), Seq(2, 3), Seq(3, 4), Seq(4, 5))
    //slideDuration 默认2滑动间隔,windowDuration批处理间隔时间1秒
  )

  testWindow(
    "tumbling(翻滚) window",
    Seq( Seq(0), Seq(1), Seq(2), Seq(3), Seq(4), Seq(5)),
    Seq( Seq(0, 1), Seq(2, 3), Seq(4, 5)),
    //slideDuration 滑动间隔,windowDuration批处理间隔时间
    Seconds(2),
    Seconds(2)
  )
  //大窗口
  testWindow(
    "larger window",
    Seq( Seq(0), Seq(1), Seq(2), Seq(3), Seq(4), Seq(5)),
    Seq( Seq(0, 1), Seq(0, 1, 2, 3), Seq(2, 3, 4, 5), Seq(4, 5)),
    //slideDuration 滑动间隔,windowDuration批处理间隔时间
    Seconds(4),
    Seconds(2)
  )

  testWindow(//注意数数windowDuration>slideDuration数据丢失
    "non-overlapping(无重叠的) window",
    Seq( Seq(0), Seq(1), Seq(2), Seq(3), Seq(4), Seq(5)),
    Seq( Seq(1, 2), Seq(4, 5)),
    //slideDuration 滑动间隔,windowDuration批处理间隔时间
    Seconds(2),
    Seconds(3)
  )
  //窗口-持久化级别
  test("window - persistence level") {
    val input = Seq( Seq(0), Seq(1), Seq(2), Seq(3), Seq(4), Seq(5))
   //分隔的时间叫作批次间隔
    val ssc = new StreamingContext(conf, batchDuration)
    val inputStream = new TestInputStream[Int](ssc, input, 1)
    //窗口间隔,返回一个包含了所有在时间滑动窗口中可见元素的新的DStream
    val windowStream1 = inputStream.window(batchDuration * 2)
    assert(windowStream1.storageLevel === StorageLevel.NONE)
    assert(inputStream.storageLevel === StorageLevel.MEMORY_ONLY_SER)
    //设置持久化
    windowStream1.persist(StorageLevel.MEMORY_ONLY)
    assert(windowStream1.storageLevel === StorageLevel.NONE)
    assert(inputStream.storageLevel === StorageLevel.MEMORY_ONLY)
    ssc.stop()
  }

  // Testing naive reduceByKeyAndWindow (without invertible function,不可逆函数)
  //测试单纯的reduceByKeyAndWindow
  testReduceByKeyAndWindow(
    "basic reduction",
    Seq( Seq(("a", 1), ("a", 3)) ),
    Seq( Seq(("a", 4)) )
  )

  testReduceByKeyAndWindow(
    //Key已存在window和新值添加到window中
    "key already in window and new value added into window",
    Seq( Seq(("a", 1)), Seq(("a", 1)) ),
    Seq( Seq(("a", 1)), Seq(("a", 2)) )
  )

  testReduceByKeyAndWindow(
    "new key added into window",//新Key值添加到Window
    Seq( Seq(("a", 1)), Seq(("a", 1), ("b", 1)) ),
    Seq( Seq(("a", 1)), Seq(("a", 2), ("b", 1)) )
  )

  testReduceByKeyAndWindow(
    "key removed from window",//从窗口中删除键
    Seq( Seq(("a", 1)), Seq(("a", 1)), Seq(), Seq() ),
    Seq( Seq(("a", 1)), Seq(("a", 2)), Seq(("a", 1)), Seq() )
  )

  testReduceByKeyAndWindow(
    "larger slide time",//较大的滑动时间
    largerSlideInput,
    largerSlideReduceOutput,
    //slideDuration 滑动间隔,windowDuration批处理间隔时间
    Seconds(4),
    Seconds(2)
  )

  testReduceByKeyAndWindow("big test", bigInput, bigReduceOutput)

  // Testing reduceByKeyAndWindow (with invertible reduce function)
  testReduceByKeyAndWindowWithInverse(
    "basic reduction",
    Seq(Seq(("a", 1), ("a", 3)) ),
    Seq(Seq(("a", 4)) )
  )

  testReduceByKeyAndWindowWithInverse(
    "key already in window and new value added into window",
    Seq( Seq(("a", 1)), Seq(("a", 1)) ),
    Seq( Seq(("a", 1)), Seq(("a", 2)) )
  )

  testReduceByKeyAndWindowWithInverse(
    "new key added into window",
    Seq( Seq(("a", 1)), Seq(("a", 1), ("b", 1)) ),
    Seq( Seq(("a", 1)), Seq(("a", 2), ("b", 1)) )
  )

  testReduceByKeyAndWindowWithInverse(
    "key removed from window",
    Seq( Seq(("a", 1)), Seq(("a", 1)), Seq(), Seq() ),
    Seq( Seq(("a", 1)), Seq(("a", 2)), Seq(("a", 1)), Seq(("a", 0)) )
  )

  testReduceByKeyAndWindowWithInverse(
    "larger slide time",
    largerSlideInput,
    largerSlideReduceOutput,
    Seconds(4),
    Seconds(2)
  )

  testReduceByKeyAndWindowWithInverse("big test", bigInput, bigReduceInvOutput)

  testReduceByKeyAndWindowWithFilteredInverse("big test", bigInput, bigReduceOutput)

  test("groupByKeyAndWindow") {
    val input = bigInput
    val expectedOutput = bigGroupByOutput.map(_.map(x => (x._1, x._2.toSet)))
    val windowDuration = Seconds(2)
    val slideDuration = Seconds(1)
    val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
    val operation = (s: DStream[(String, Int)]) => {
      s.groupByKeyAndWindow(windowDuration, slideDuration).map(x => (x._1, x._2.toSet))
    }
    testOperation(input, operation, expectedOutput, numBatches, true)
  }

  test("countByWindow") {
    val input = Seq(Seq(1), Seq(1), Seq(1, 2), Seq(0), Seq(), Seq() )
    val expectedOutput = Seq( Seq(1), Seq(2), Seq(3), Seq(3), Seq(1), Seq(0))
    val windowDuration = Seconds(2)
    val slideDuration = Seconds(1)
    val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
    val operation = (s: DStream[Int]) => {
    //countByWindow对所有元素进行count操作后,每个RDD都只包含一个元素的新的DStream
      s.countByWindow(windowDuration, slideDuration).map(_.toInt)
    }
    testOperation(input, operation, expectedOutput, numBatches, true)
  }

  test("countByValueAndWindow") {
    val input = Seq(Seq("a"), Seq("b", "b"), Seq("a", "b"))
    val expectedOutput = Seq( Seq(("a", 1)), Seq(("a", 1), ("b", 2)), Seq(("a", 1), ("b", 3)))
    val windowDuration = Seconds(2)
    val slideDuration = Seconds(1)
    val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
    val operation = (s: DStream[String]) => {
      s.countByValueAndWindow(windowDuration, slideDuration).map(x => (x._1, x._2.toInt))
    }
    testOperation(input, operation, expectedOutput, numBatches, true)
  }


  // Helper functions
   //辅助功能
  def testWindow(
    name: String,
    input: Seq[Seq[Int]],
    expectedOutput: Seq[Seq[Int]],
    //slideDuration 滑动窗口的宽度
    windowDuration: Duration = Seconds(2),
    //windowDuration 窗口滑动的频率,这两个参数必须是 batch size 的倍数
    slideDuration: Duration = Seconds(1)
    ) {
    test("window - " + name) {
      //批次数
      val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
      //slideDuration 滑动间隔,windowDuration批处理间隔时间
      val operation = (s: DStream[Int]) => s.window(windowDuration, slideDuration)
      testOperation(input, operation, expectedOutput, numBatches, true)
    }
  }

  def testReduceByKeyAndWindow(
    name: String,
    input: Seq[Seq[(String, Int)]],
    expectedOutput: Seq[Seq[(String, Int)]],
    windowDuration: Duration = Seconds(2),
    slideDuration: Duration = Seconds(1)
    ) {
    test("reduceByKeyAndWindow - " + name) {
      logInfo("reduceByKeyAndWindow - " + name)
      val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
      val operation = (s: DStream[(String, Int)]) => {
        //基于窗口对原DStream中元素进行聚合得到新的DStream,与reduceByKey相似
        //应用到一个(K,V)对组成的DStream上,返回一个由(K,V)对组成的新的DStream。每一个key的值均由给定的reduce函数聚集起来
        s.reduceByKeyAndWindow((x: Int, y: Int) => x + y, windowDuration, slideDuration)
      }
      testOperation(input, operation, expectedOutput, numBatches, true)
    }
  }

  def testReduceByKeyAndWindowWithInverse(
    name: String,
    input: Seq[Seq[(String, Int)]],
    expectedOutput: Seq[Seq[(String, Int)]],
    windowDuration: Duration = Seconds(2),
    slideDuration: Duration = Seconds(1)
  ) {
    test("reduceByKeyAndWindow with inverse function - " + name) {
      logInfo("reduceByKeyAndWindow with inverse function - " + name)
      val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
      val operation = (s: DStream[(String, Int)]) => {
        s.reduceByKeyAndWindow(_ + _, _ - _, windowDuration, slideDuration)
	      //在interval周期后给生成的RDD设置检查点
         .checkpoint(Seconds(100)) // Large value to avoid effect of RDD checkpointing
      }
      testOperation(input, operation, expectedOutput, numBatches, true)
    }
  }

  def testReduceByKeyAndWindowWithFilteredInverse(
      name: String,
      input: Seq[Seq[(String, Int)]],
      expectedOutput: Seq[Seq[(String, Int)]],
      windowDuration: Duration = Seconds(2),
      slideDuration: Duration = Seconds(1)
    ) {
    test("reduceByKeyAndWindow with inverse and filter functions - " + name) {
      logInfo("reduceByKeyAndWindow with inverse and filter functions - " + name)
      val numBatches = expectedOutput.size * (slideDuration / batchDuration).toInt
      val filterFunc = (p: (String, Int)) => p._2 != 0
      val operation = (s: DStream[(String, Int)]) => {
        s.reduceByKeyAndWindow(_ + _, _ - _, windowDuration, slideDuration, filterFunc = filterFunc)
          .persist()
	      //在interval周期后给生成的RDD设置检查点
          .checkpoint(Seconds(100)) // Large value to avoid effect of RDD checkpointing
      }
      testOperation(input, operation, expectedOutput, numBatches, true)
    }
  }
}
