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

package org.apache.spark.sql.execution.joins

import java.util.{HashMap => JavaHashMap}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.metric.LongSQLMetric
import org.apache.spark.util.collection.CompactBuffer

@DeveloperApi
trait HashOuterJoin {
  self: SparkPlan =>

  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val joinType: JoinType
  val condition: Option[Expression]
  val left: SparkPlan
  val right: SparkPlan

  override def output: Seq[Attribute] = {
    joinType match {
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case x =>
        throw new IllegalArgumentException(s"HashOuterJoin should not take $x as the JoinType")
    }
  }

  protected[this] lazy val (buildPlan, streamedPlan) = joinType match {
    case RightOuter => (left, right)
    case LeftOuter => (right, left)
    case x =>
      throw new IllegalArgumentException(
        s"HashOuterJoin should not take $x as the JoinType")
  }

  protected[this] lazy val (buildKeys, streamedKeys) = joinType match {
    case RightOuter => (leftKeys, rightKeys)
    case LeftOuter => (rightKeys, leftKeys)
    case x =>
      throw new IllegalArgumentException(
        s"HashOuterJoin should not take $x as the JoinType")
  }

  protected[this] def isUnsafeMode: Boolean = {
    (self.codegenEnabled && self.unsafeEnabled && joinType != FullOuter
      && UnsafeProjection.canSupport(buildKeys)
      && UnsafeProjection.canSupport(self.schema))
  }

  override def outputsUnsafeRows: Boolean = isUnsafeMode
  override def canProcessUnsafeRows: Boolean = isUnsafeMode
  override def canProcessSafeRows: Boolean = !isUnsafeMode

  protected def buildKeyGenerator: Projection =
    if (isUnsafeMode) {
      UnsafeProjection.create(buildKeys, buildPlan.output)
    } else {
      newMutableProjection(buildKeys, buildPlan.output)()
    }

  protected[this] def streamedKeyGenerator: Projection = {
    if (isUnsafeMode) {
      UnsafeProjection.create(streamedKeys, streamedPlan.output)
    } else {
      newProjection(streamedKeys, streamedPlan.output)
    }
  }

  protected[this] def resultProjection: InternalRow => InternalRow = {
    if (isUnsafeMode) {
      UnsafeProjection.create(self.schema)
    } else {
      identity[InternalRow]
    }
  }

  @transient private[this] lazy val DUMMY_LIST = CompactBuffer[InternalRow](null)
  @transient protected[this] lazy val EMPTY_LIST = CompactBuffer[InternalRow]()

  @transient private[this] lazy val leftNullRow = new GenericInternalRow(left.output.length)
  @transient private[this] lazy val rightNullRow = new GenericInternalRow(right.output.length)
  @transient private[this] lazy val boundCondition =
    newPredicate(condition.getOrElse(Literal(true)), left.output ++ right.output)

  // TODO we need to rewrite all of the iterators with our own implementation instead of the Scala
  // iterator for performance purpose.

  protected[this] def leftOuterIterator(
      key: InternalRow,
      joinedRow: JoinedRow,
      rightIter: Iterable[InternalRow],
      resultProjection: InternalRow => InternalRow,
      numOutputRows: LongSQLMetric): Iterator[InternalRow] = {
    val ret: Iterable[InternalRow] = {
      if (!key.anyNull) {
        val temp = if (rightIter != null) {
          rightIter.collect {
            case r if boundCondition(joinedRow.withRight(r)) => {
              numOutputRows += 1
              resultProjection(joinedRow).copy()
            }
          }
        } else {
          List.empty
        }
        if (temp.isEmpty) {
          numOutputRows += 1
          resultProjection(joinedRow.withRight(rightNullRow)) :: Nil
        } else {
          temp
        }
      } else {
        numOutputRows += 1
        resultProjection(joinedRow.withRight(rightNullRow)) :: Nil
      }
    }
    ret.iterator
  }

  protected[this] def rightOuterIterator(
      key: InternalRow,
      leftIter: Iterable[InternalRow],
      joinedRow: JoinedRow,
      resultProjection: InternalRow => InternalRow,
      numOutputRows: LongSQLMetric): Iterator[InternalRow] = {
    val ret: Iterable[InternalRow] = {
      if (!key.anyNull) {
        val temp = if (leftIter != null) {
          leftIter.collect {
            case l if boundCondition(joinedRow.withLeft(l)) => {
              numOutputRows += 1
              resultProjection(joinedRow).copy()
            }
          }
        } else {
          List.empty
        }
        if (temp.isEmpty) {
          numOutputRows += 1
          resultProjection(joinedRow.withLeft(leftNullRow)) :: Nil
        } else {
          temp
        }
      } else {
        numOutputRows += 1
        resultProjection(joinedRow.withLeft(leftNullRow)) :: Nil
      }
    }
    ret.iterator
  }

  protected[this] def fullOuterIterator(
      key: InternalRow, leftIter: Iterable[InternalRow], rightIter: Iterable[InternalRow],
      joinedRow: JoinedRow, numOutputRows: LongSQLMetric): Iterator[InternalRow] = {
    if (!key.anyNull) {
      // Store the positions of records in right, if one of its associated row satisfy
      // the join condition.
      //如果其关联行之一满足连接条件,则将记录的位置存储在右侧
      val rightMatchedSet = scala.collection.mutable.Set[Int]()
      leftIter.iterator.flatMap[InternalRow] { l =>
        joinedRow.withLeft(l)
        var matched = false
        rightIter.zipWithIndex.collect {
          // 1. For those matched (satisfy the join condition) records with both sides filled,
          //    append them directly
          //对于那些匹配(满足连接条件)记录并填充两边的记录,直接附加它们

          case (r, idx) if boundCondition(joinedRow.withRight(r)) =>
            numOutputRows += 1
            matched = true
            // if the row satisfy the join condition, add its index into the matched set
            //如果行满足连接条件,则将其索引添加到匹配的集合中
            rightMatchedSet.add(idx)
            joinedRow.copy()

        } ++ DUMMY_LIST.filter(_ => !matched).map( _ => {
          // 2. For those unmatched records in left, append additional records with empty right.
          //对于左边那些不匹配的记录,添加空白右边的附加记录

          // DUMMY_LIST.filter(_ => !matched) is a tricky way to add additional row,
          // as we don't know whether we need to append it until finish iterating all
          // of the records in right side.
          // If we didn't get any proper row, then append a single row with empty right.
          numOutputRows += 1
          joinedRow.withRight(rightNullRow).copy()
        })
      } ++ rightIter.zipWithIndex.collect {
        // 3. For those unmatched records in right, append additional records with empty left.
        //对于右边那些不匹配的记录,附加空左边的附加记录。

        // Re-visiting the records in right, and append additional row with empty left, if its not
        // in the matched set.
        case (r, idx) if !rightMatchedSet.contains(idx) =>
          numOutputRows += 1
          joinedRow(leftNullRow, r).copy()
      }
    } else {
      leftIter.iterator.map[InternalRow] { l =>
        numOutputRows += 1
        joinedRow(l, rightNullRow).copy()
      } ++ rightIter.iterator.map[InternalRow] { r =>
        numOutputRows += 1
        joinedRow(leftNullRow, r).copy()
      }
    }
  }

  // This is only used by FullOuter
  //这仅供FullOuter使用
  protected[this] def buildHashTable(
      iter: Iterator[InternalRow],
      numIterRows: LongSQLMetric,
      keyGenerator: Projection): JavaHashMap[InternalRow, CompactBuffer[InternalRow]] = {
    val hashTable = new JavaHashMap[InternalRow, CompactBuffer[InternalRow]]()
    while (iter.hasNext) {
      val currentRow = iter.next()
      numIterRows += 1
      val rowKey = keyGenerator(currentRow)

      var existingMatchList = hashTable.get(rowKey)
      if (existingMatchList == null) {
        existingMatchList = new CompactBuffer[InternalRow]()
        hashTable.put(rowKey.copy(), existingMatchList)
      }

      existingMatchList += currentRow.copy()
    }

    hashTable
  }
}
