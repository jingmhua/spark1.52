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
import org.apache.spark.ml._
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.feature
import org.apache.spark.mllib.linalg.{Vector, VectorUDT}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType}

/**
 * Params for [[PCA]] and [[PCAModel]].
 */
private[feature] trait PCAParams extends Params with HasInputCol with HasOutputCol {

  /**
   * The number of principal components.
   * @group param
   */
  final val k: IntParam = new IntParam(this, "k", "the number of principal components")

  /** @group getParam */
  def getK: Int = $(k)

}

/**
 * :: Experimental ::
 * PCA trains a model to project vectors to a low-dimensional space using PCA.
  * PCA使用PCA训练模型以将向量投影到低维空间
 */
@Experimental
class PCA (override val uid: String) extends Estimator[PCAModel] with PCAParams {

  def this() = this(Identifiable.randomUID("pca"))

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  def setK(value: Int): this.type = set(k, value)

  /**
   * Computes a [[PCAModel]] that contains the principal components of the input vectors.
   */
  override def fit(dataset: DataFrame): PCAModel = {
    transformSchema(dataset.schema, logging = true)
    val input = dataset.select($(inputCol)).map { case Row(v: Vector) => v}
    val pca = new feature.PCA(k = $(k))
    val pcaModel = pca.fit(input)
    copyValues(new PCAModel(uid, pcaModel).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    val inputType = schema($(inputCol)).dataType
    require(inputType.isInstanceOf[VectorUDT],
      s"Input column ${$(inputCol)} must be a vector column")
    require(!schema.fieldNames.contains($(outputCol)),
      s"Output column ${$(outputCol)} already exists.")
    val outputFields = schema.fields :+ StructField($(outputCol), new VectorUDT, false)
    StructType(outputFields)
  }

  override def copy(extra: ParamMap): PCA = defaultCopy(extra)
}

/**
 * :: Experimental ::
 * Model fitted by [[PCA]].
 */
@Experimental
class PCAModel private[ml] (
    override val uid: String,
    pcaModel: feature.PCAModel)
  extends Model[PCAModel] with PCAParams {

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /**
   * Transform a vector by computed Principal Components.
   * NOTE: Vectors to be transformed must be the same length
   * as the source vectors given to [[PCA.fit()]].
   */
  override def transform(dataset: DataFrame): DataFrame = {
    transformSchema(dataset.schema, logging = true)
    val pcaOp = udf { pcaModel.transform _ }
    dataset.withColumn($(outputCol), pcaOp(col($(inputCol))))
  }

  override def transformSchema(schema: StructType): StructType = {
    val inputType = schema($(inputCol)).dataType
    require(inputType.isInstanceOf[VectorUDT],
      s"Input column ${$(inputCol)} must be a vector column")
    require(!schema.fieldNames.contains($(outputCol)),
      s"Output column ${$(outputCol)} already exists.")
    val outputFields = schema.fields :+ StructField($(outputCol), new VectorUDT, false)
    StructType(outputFields)
  }

  override def copy(extra: ParamMap): PCAModel = {
    val copied = new PCAModel(uid, pcaModel)
    copyValues(copied, extra).setParent(parent)
  }
}
