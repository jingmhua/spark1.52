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

package org.apache.spark.ml.feature

import org.apache.spark.annotation.Experimental
import org.apache.spark.ml.UnaryTransformer
import org.apache.spark.ml.param._
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.types.{ArrayType, DataType, StringType}

/**
 * :: Experimental ::
 * A feature transformer that converts the input array of strings into an array of n-grams. Null
 * values in the input array are ignored.
  * 一种功能转换器,用于将输入的字符串数组转换为n-gram数组,输入数组中的空值将被忽略,
 * It returns an array of n-grams where each n-gram is represented by a space-separated string of
 * words.
  * 它返回一个n-gram数组,其中每个n-gram由一个以空格分隔的单词串表示
 *
 * When the input is empty, an empty array is returned.
 * When the input array length is less than n (number of elements per n-gram), no n-grams are
 * returned.
 */
@Experimental
class NGram(override val uid: String)
  extends UnaryTransformer[Seq[String], Seq[String], NGram] {

  def this() = this(Identifiable.randomUID("ngram"))

  /**
   * Minimum n-gram length, >= 1.
    * 最小n-gram长度> = 1
   * Default: 2, bigram features
   * @group param
   */
  val n: IntParam = new IntParam(this, "n", "number elements per n-gram (>=1)",
    ParamValidators.gtEq(1))

  /** @group setParam */
  def setN(value: Int): this.type = set(n, value)

  /** @group getParam */
  def getN: Int = $(n)

  setDefault(n -> 2)

  override protected def createTransformFunc: Seq[String] => Seq[String] = {
    _.iterator.sliding($(n)).withPartial(false).map(_.mkString(" ")).toSeq
  }

  override protected def validateInputType(inputType: DataType): Unit = {
    require(inputType.sameType(ArrayType(StringType)),
      s"Input type must be ArrayType(StringType) but got $inputType.")
  }

  override protected def outputDataType: DataType = new ArrayType(StringType, false)
}
