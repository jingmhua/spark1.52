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

package org.apache.spark.mllib.linalg

import breeze.linalg.{DenseMatrix => BDM, CSCMatrix => BSM}

import org.apache.spark.SparkFunSuite

class BreezeMatrixConversionSuite extends SparkFunSuite {
  test("dense matrix to breeze") {//稠密矩阵转换breeze矩阵
    val mat = Matrices.dense(3, 2, Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    val breeze = mat.toBreeze.asInstanceOf[BDM[Double]]
    assert(breeze.rows === mat.numRows)
    assert(breeze.cols === mat.numCols)
    assert(breeze.data.eq(mat.asInstanceOf[DenseMatrix].values), "should not copy data")
  }

  test("dense breeze matrix to matrix") {//稠密breeze矩阵转换矩阵
    val breeze = new BDM[Double](3, 2, Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    val mat = Matrices.fromBreeze(breeze).asInstanceOf[DenseMatrix]
    assert(mat.numRows === breeze.rows)
    assert(mat.numCols === breeze.cols)
    assert(mat.values.eq(breeze.data), "should not copy data")
    // transposed matrix 转置矩阵
    val matTransposed = Matrices.fromBreeze(breeze.t).asInstanceOf[DenseMatrix]
    assert(matTransposed.numRows === breeze.cols)
    assert(matTransposed.numCols === breeze.rows)
    assert(matTransposed.values.eq(breeze.data), "should not copy data")
  }

  test("sparse matrix to breeze") {//稀疏矩阵转换breeze矩阵
    val values = Array(1.0, 2.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(1, 2, 1, 2)
    val mat = Matrices.sparse(3, 2, colPtrs, rowIndices, values)
    val breeze = mat.toBreeze.asInstanceOf[BSM[Double]]
    assert(breeze.rows === mat.numRows)
    assert(breeze.cols === mat.numCols)
    assert(breeze.data.eq(mat.asInstanceOf[SparseMatrix].values), "should not copy data")
  }

  test("sparse breeze matrix to sparse matrix") {//稀疏breeze矩阵转换稀疏矩阵
    val values = Array(1.0, 2.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(1, 2, 1, 2)
    val breeze = new BSM[Double](values, 3, 2, colPtrs, rowIndices)
    val mat = Matrices.fromBreeze(breeze).asInstanceOf[SparseMatrix]
    assert(mat.numRows === breeze.rows)
    assert(mat.numCols === breeze.cols)
    assert(mat.values.eq(breeze.data), "should not copy data")
    val matTransposed = Matrices.fromBreeze(breeze.t).asInstanceOf[SparseMatrix]
    assert(matTransposed.numRows === breeze.cols)
    assert(matTransposed.numCols === breeze.rows)
    assert(!matTransposed.values.eq(breeze.data), "has to copy data")
  }
}
