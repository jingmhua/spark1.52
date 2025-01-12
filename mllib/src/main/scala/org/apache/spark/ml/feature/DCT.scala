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

import edu.emory.mathcs.jtransforms.dct._

import org.apache.spark.annotation.Experimental
import org.apache.spark.ml.UnaryTransformer
import org.apache.spark.ml.param.BooleanParam
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.linalg.{Vector, VectorUDT, Vectors}
import org.apache.spark.sql.types.DataType

/**
 * :: Experimental ::
 * A feature transformer that takes the 1D discrete cosine transform of a real vector. No zero
 * padding is performed on the input vector.
  * 一个特征变换器,它采用实矢量的一维离散余弦变换,在输入向量上不执行零填充
 * It returns a real vector of the same length representing the DCT. The return vector is scaled
 * such that the transform matrix is unitary (aka scaled DCT-II).
  * 它返回表示DCT的相同长度的实数向量,缩放返回向量,使得变换矩阵是单一的（也称为缩放的DCT-II）
 *
 * More information on [[https://en.wikipedia.org/wiki/Discrete_cosine_transform#DCT-II Wikipedia]].
 */
@Experimental
class DCT(override val uid: String)
  extends UnaryTransformer[Vector, Vector, DCT] {

  def this() = this(Identifiable.randomUID("dct"))

  /**
   * Indicates whether to perform the inverse DCT (true) or forward DCT (false).
    * 指示是执行逆DCT(true)还是转发DCT(false)
   * Default: false
   * @group param
   */
  def inverse: BooleanParam = new BooleanParam(
    this, "inverse", "Set transformer to perform inverse DCT")

  /** @group setParam */
  def setInverse(value: Boolean): this.type = set(inverse, value)

  /** @group getParam */
  def getInverse: Boolean = $(inverse)

  setDefault(inverse -> false)

  override protected def createTransformFunc: Vector => Vector = { vec =>
    val result = vec.toArray
    val jTransformer = new DoubleDCT_1D(result.length)
    if ($(inverse)) jTransformer.inverse(result, true) else jTransformer.forward(result, true)
    Vectors.dense(result)
  }

  override protected def validateInputType(inputType: DataType): Unit = {
    require(inputType.isInstanceOf[VectorUDT], s"Input type must be VectorUDT but got $inputType.")
  }

  override protected def outputDataType: DataType = new VectorUDT
}
