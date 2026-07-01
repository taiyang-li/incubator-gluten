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
package org.apache.gluten.metrics

import org.apache.gluten.metrics.Metrics.SingleMetric
import org.apache.gluten.substrait.JoinParams

import org.apache.spark.sql.execution.metric.SQLMetric

import java.util

trait SharedJoinMetricsUpdater extends MetricsUpdater {
  def updateJoinMetrics(
      joinMetrics: util.ArrayList[OperatorMetrics],
      singleMetrics: SingleMetric,
      joinParams: JoinParams): Unit
}

abstract class SharedJoinMetricsUpdaterBase(val metrics: Map[String, SQLMetric])
  extends SharedJoinMetricsUpdater {
  val postProjectionCpuCount: SQLMetric = metrics("postProjectionCpuCount")
  val postProjectionWallNanos: SQLMetric = metrics("postProjectionWallNanos")
  val numOutputRows: SQLMetric = metrics("numOutputRows")
  val numOutputVectors: SQLMetric = metrics("numOutputVectors")
  val numOutputBytes: SQLMetric = metrics("numOutputBytes")

  final override def updateJoinMetrics(
      joinMetrics: util.ArrayList[OperatorMetrics],
      singleMetrics: SingleMetric,
      joinParams: JoinParams): Unit = {
    if (joinParams != null && joinParams.postProjectionNeeded) {
      val postProjectMetrics = joinMetrics.remove(0)
      postProjectionCpuCount += postProjectMetrics.cpuCount
      postProjectionWallNanos += postProjectMetrics.wallNanos
      numOutputRows += postProjectMetrics.outputRows
      numOutputVectors += postProjectMetrics.outputVectors
      numOutputBytes += postProjectMetrics.outputBytes
    }

    updateJoinMetricsInternal(joinMetrics, joinParams)
  }

  protected def updateJoinMetricsInternal(
      joinMetrics: util.ArrayList[OperatorMetrics],
      joinParams: JoinParams): Unit
}
