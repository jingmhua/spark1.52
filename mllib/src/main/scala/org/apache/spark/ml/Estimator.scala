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

package org.apache.spark.ml

import scala.annotation.varargs

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.param.{ParamMap, ParamPair}
import org.apache.spark.sql.DataFrame

/**
 * :: DeveloperApi ::
 * Abstract class for estimators that fit models to data.
  * 适用于数据模型的估算器的抽象类
 */
@DeveloperApi
abstract class Estimator[M <: Model[M]] extends PipelineStage {

  /**
   * Fits a single model to the input data with optional parameters.
    * 使用可选参数使单个模型适合输入数据
   *
   * @param dataset input dataset
   * @param firstParamPair the first param pair, overrides embedded params
   * @param otherParamPairs other param pairs.  These values override any specified in this
   *                        Estimator's embedded ParamMap.
   * @return fitted model
   */
  @varargs
  def fit(dataset: DataFrame, firstParamPair: ParamPair[_], otherParamPairs: ParamPair[_]*): M = {
    val map = new ParamMap()
      .put(firstParamPair)
      .put(otherParamPairs: _*)
    fit(dataset, map)
  }

  /**
   * Fits a single model to the input data with provided parameter map.
    * 使用提供的参数映射使单个模型适合输入数据
   *
   * @param dataset input dataset
   * @param paramMap Parameter map.
   *                 These values override any specified in this Estimator's embedded ParamMap.
   * @return fitted model
   */
  def fit(dataset: DataFrame, paramMap: ParamMap): M = {
    copy(paramMap).fit(dataset)
  }

  /**
   * Fits a model to the input data.
    * 使模型适合输入数据
   */
  def fit(dataset: DataFrame): M

  /**
   * Fits multiple models to the input data with multiple sets of parameters.
    * 使用多组参数使多个模型适合输入数据
   * The default implementation uses a for loop on each parameter map.
    * 默认实现在每个参数映射上使用for循环
   * Subclasses could override this to optimize multi-model training.
   *子类可以覆盖它以优化多模型训练
   * @param dataset input dataset
   * @param paramMaps An array of parameter maps.
   *                  These values override any specified in this Estimator's embedded ParamMap.
   * @return fitted models, matching the input parameter maps
   */
  def fit(dataset: DataFrame, paramMaps: Array[ParamMap]): Seq[M] = {
    paramMaps.map(fit(dataset, _))
  }

  override def copy(extra: ParamMap): Estimator[M]
}
