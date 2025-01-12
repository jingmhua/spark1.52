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
//持续时间测试套件
class DurationSuite extends TestSuiteBase {

  test("less") {//小于
    assert(new Duration(999) < new Duration(1000))
    assert(new Duration(0) < new Duration(1))
    assert(!(new Duration(1000) < new Duration(999)))
    assert(!(new Duration(1000) < new Duration(1000)))
  }

  test("lessEq") {//小于等于
    assert(new Duration(999) <= new Duration(1000))
    assert(new Duration(0) <= new Duration(1))
    assert(!(new Duration(1000) <= new Duration(999)))
    assert(new Duration(1000) <= new Duration(1000))
  }

  test("greater") {//大于
    assert(!(new Duration(999) > new Duration(1000)))
    assert(!(new Duration(0) > new Duration(1)))
    assert(new Duration(1000) > new Duration(999))
    assert(!(new Duration(1000) > new Duration(1000)))
  }

  test("greaterEq") {//大于等于
    assert(!(new Duration(999) >= new Duration(1000)))
    assert(!(new Duration(0) >= new Duration(1)))
    assert(new Duration(1000) >= new Duration(999))
    assert(new Duration(1000) >= new Duration(1000))
  }

  test("plus") {//加
    assert((new Duration(1000) + new Duration(100)) == new Duration(1100))
    assert((new Duration(1000) + new Duration(0)) == new Duration(1000))
  }

  test("minus") {//减
    assert((new Duration(1000) - new Duration(100)) == new Duration(900))
    assert((new Duration(1000) - new Duration(0)) == new Duration(1000))
    assert((new Duration(1000) - new Duration(1000)) == new Duration(0))
  }

  test("times") {//乘
    assert((new Duration(100) * 2) == new Duration(200))
    assert((new Duration(100) * 1) == new Duration(100))
    assert((new Duration(100) * 0) == new Duration(0))
  }

  test("div") {//除
    assert((new Duration(1000) / new Duration(5)) == 200.0)
    assert((new Duration(1000) / new Duration(1)) == 1000.0)
    assert((new Duration(1000) / new Duration(1000)) == 1.0)
    assert((new Duration(1000) / new Duration(2000)) == 0.5)
  }

  test("isMultipleOf") {//
    assert(new Duration(1000).isMultipleOf(new Duration(5)))
    assert(new Duration(1000).isMultipleOf(new Duration(1000)))
    assert(new Duration(1000).isMultipleOf(new Duration(1)))
    assert(!new Duration(1000).isMultipleOf(new Duration(6)))
  }

  test("min") {//最小值
    assert(new Duration(999).min(new Duration(1000)) == new Duration(999))
    assert(new Duration(1000).min(new Duration(999)) == new Duration(999))
    assert(new Duration(1000).min(new Duration(1000)) == new Duration(1000))
  }

  test("max") {//最大值
    assert(new Duration(999).max(new Duration(1000)) == new Duration(1000))
    assert(new Duration(1000).max(new Duration(999)) == new Duration(1000))
    assert(new Duration(1000).max(new Duration(1000)) == new Duration(1000))
  }

  test("isZero") {//是否为0值
    assert(new Duration(0).isZero)
    assert(!(new Duration(1).isZero))
  }

  test("Milliseconds") {//毫秒
    assert(new Duration(100) == Milliseconds(100))
  }

  test("Seconds") {//秒
    assert(new Duration(30 * 1000) == Seconds(30))
  }

  test("Minutes") {//分
    assert(new Duration(2 * 60 * 1000) == Minutes(2))
  }

}
