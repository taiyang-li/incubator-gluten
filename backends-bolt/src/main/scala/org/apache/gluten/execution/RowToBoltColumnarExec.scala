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
package org.apache.gluten.execution

import org.apache.gluten.config.BoltConfig

import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.plans.physical.BroadcastMode
import org.apache.spark.sql.execution.{BroadcastUtils, SparkPlan}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch

case class RowToBoltColumnarExec(child: SparkPlan) extends SharedRowToColumnarExec(child) {

  override protected def preferredBatchBytes: Long = BoltConfig.get.boltPreferredBatchBytes

  override protected def sparkToBackendUnsafe[F, T](
      sc: SparkContext,
      mode: BroadcastMode,
      schema: StructType,
      relation: Broadcast[F],
      itrTransformer: Iterator[InternalRow] => Iterator[ColumnarBatch]): Broadcast[T] = {
    BroadcastUtils.sparkToBoltUnsafe(sc, mode, schema, relation, itrTransformer)
  }

  // For spark 3.2.
  protected def withNewChildInternal(newChild: SparkPlan): RowToBoltColumnarExec =
    copy(child = newChild)
}

object RowToBoltColumnarExec {

  def toColumnarBatchIterator(
      it: Iterator[InternalRow],
      schema: StructType,
      columnBatchSize: Int,
      columnBatchBytes: Long): Iterator[ColumnarBatch] =
    SharedRowToColumnarExec.toColumnarBatchIterator(it, schema, columnBatchSize, columnBatchBytes)

  def toColumnarBatchIterator(
      it: Iterator[InternalRow],
      schema: StructType,
      numInputRows: SQLMetric,
      numOutputBatches: SQLMetric,
      convertTime: SQLMetric,
      columnBatchSize: Int,
      columnBatchBytes: Long): Iterator[ColumnarBatch] =
    SharedRowToColumnarExec.toColumnarBatchIterator(
      it,
      schema,
      numInputRows,
      numOutputBatches,
      convertTime,
      columnBatchSize,
      columnBatchBytes)
}
