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

import org.apache.gluten.backendsapi.BackendsApiManager
import org.apache.gluten.exception.GlutenNotSupportException
import org.apache.gluten.sql.shims.SparkShimLoader
import org.apache.gluten.substrait.rel.{PaimonLocalFilesBuilder, SplitInfo}
import org.apache.gluten.substrait.rel.LocalFilesNode.ReadFileFormat

import org.apache.spark.Partition
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.softaffinity.SoftAffinity
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, DynamicPruningExpression, Expression, Literal}
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.connector.read.Scan
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch

import org.apache.paimon.CoreOptions
import org.apache.paimon.spark.{PaimonBaseScan, PaimonInputPartition, PaimonScan}
import org.apache.paimon.spark.schema.PaimonMetadataColumn.SUPPORTED_METADATA_COLUMNS
import org.apache.paimon.spark.source.PaimonConfig
import org.apache.paimon.table.{DataTable, FileStoreTable}
import org.apache.paimon.table.source.DataSplit
import org.apache.paimon.types.DecimalType

import java.lang.{Integer => JInteger}
import java.lang.{Long => JLong}
import java.util.{HashMap => JHashMap, Map => JMap}

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.collection.mutable

abstract class AbstractPaimonScanTransformer(
    override val output: Seq[AttributeReference],
    @transient override val scan: Scan,
    override val runtimeFilters: Seq[Expression],
    @transient override val table: Table,
    override val keyGroupedPartitioning: Option[Seq[Expression]] = None,
    override val commonPartitionValues: Option[Seq[(InternalRow, Int)]] = None,
    override val pushDownFilters: Option[Seq[Expression]] = None)
  extends BatchScanExecTransformerBase(
    output = output,
    scan = scan,
    runtimeFilters = runtimeFilters,
    keyGroupedPartitioning = keyGroupedPartitioning,
    table = table,
    commonPartitionValues = commonPartitionValues
  )
  with Logging {

  @transient private lazy val shim: PaimonSparkShim = new PaimonSparkShimImpl()

  protected lazy val tableProperties: HashMap[String, String] = {
    val map = HashMap.newBuilder[String, String]
    scan match {
      case paimonScan: PaimonScan =>
        val paimonTable = paimonScan.table
        val coreOptions = new CoreOptions(paimonTable.options())
        coreOptions.toMap.forEach((key, value) => map += (key -> value))
        if (SQLConf.get.getConf(PaimonConfig.PAIMON_NATIVE_SPLIT_ENABLED)) {
          map += ("isPaimon" -> "true")
        }
        val primaryKeys = paimonTable.primaryKeys()
        if (!primaryKeys.isEmpty) {
          map += ("primary-key" -> String.join(",", primaryKeys))
        }
      case _ =>
    }
    map.result()
  }

  private lazy val coreOptions: CoreOptions = scan match {
    case paimonScan: PaimonScan =>
      paimonScan.table match {
        case dataTable: DataTable =>
          dataTable.coreOptions()
        case _ =>
          throw new GlutenNotSupportException("Only support Paimon DataTable.")
      }
    case _ =>
      throw new GlutenNotSupportException("Only support PaimonScan.")
  }

  override def getPartitionSchema: StructType = scan match {
    case paimonScan: PaimonScan =>
      val partitionKeys = paimonScan.table.partitionKeys()
      StructType(scan.readSchema().filter(field => partitionKeys.contains(field.name)))
    case _ =>
      throw new GlutenNotSupportException("Only support PaimonScan.")
  }

  override def getDataSchema: StructType = CharVarcharUtils.replaceCharVarcharWithStringInSchema(
    scan.asInstanceOf[PaimonBaseScan].readSchema())

  override lazy val fileFormat: ReadFileFormat = {
    val formatStr = coreOptions.fileFormatString()
    if ("parquet".equalsIgnoreCase(formatStr)) {
      ReadFileFormat.ParquetReadFormat
    } else if ("orc".equalsIgnoreCase(formatStr)) {
      ReadFileFormat.OrcReadFormat
    } else {
      ReadFileFormat.UnknownFormat
    }
  }

  override def doValidateInternal(): ValidationResult = {
    val result = AbstractPaimonScanTransformer.supportsBatchScan(scan)
    if (result.ok()) {
      return super.doValidateInternal()
    }
    result
  }

  override def doExecuteColumnar(): RDD[ColumnarBatch] = throw new UnsupportedOperationException()

  override protected def rewriteNativeScanFilters(filters: Seq[Expression]): Seq[Expression] =
    scan match {
      case paimonScan: PaimonScan =>
        BackendsApiManager.getSparkPlanExecApiInstance.rewritePaimonPushdownFilters(
          filters,
          paimonScan.table.primaryKeys().asScala.toSet,
          SUPPORTED_METADATA_COLUMNS.iterator.toSet)
      case _ => filters
    }

  override def getSplitInfosFromPartitions(
      partitions: Seq[(Partition, ReadFileFormat)]): Seq[SplitInfo] = {
    val partitionComputer = scan match {
      case paimonScan: PaimonScan => shim.getInternalPartitionComputer(paimonScan)
      case _ => throw new GlutenNotSupportException("Only support PaimonScan.")
    }
    val paimonScan = scan.asInstanceOf[PaimonScan]
    val primaryKeys = paimonScan.table.primaryKeys()
    val useHiveSplit = !SQLConf.get.getConf(PaimonConfig.PAIMON_NATIVE_SPLIT_ENABLED)

    partitions.map {
      case (partition: SparkDataSourceRDDPartition, _) =>
        val paths = mutable.ListBuffer.empty[String]
        val starts = mutable.ListBuffer.empty[JLong]
        val lengths = mutable.ListBuffer.empty[JLong]
        val partitionColumns = mutable.ListBuffer.empty[JMap[String, String]]
        val buckets = mutable.ListBuffer.empty[JInteger]
        val firstRowIds = mutable.ListBuffer.empty[JLong]
        val maxSequenceNumbers = mutable.ListBuffer.empty[JLong]
        val splitGroups = mutable.ListBuffer.empty[JInteger]
        val allRawConvertible = partition.inputPartitions.forall {
          case paimonPartition: PaimonInputPartition =>
            paimonPartition.splits.forall(_.convertToRawFiles().isPresent)
          case _ => false
        }

        partition.inputPartitions.foreach {
          case paimonPartition: PaimonInputPartition =>
            paimonPartition.splits.zipWithIndex.foreach {
              case (split: DataSplit, splitIdx) =>
                if (shim.hasBeforeFiles(split)) {
                  throw new UnsupportedOperationException("Do not support before files")
                }
                val partitionRow =
                  partitionComputer.generatePartValues(shim.getSplitPartition(split))
                val fileMetas = split.dataFiles().asScala
                val bucket = split.bucket()
                paths ++= fileMetas.map(
                  file => {
                    val bucketPath = shim.getBucketPath(split, file)
                    bucketPath + "/" + file.fileName()
                  })
                starts ++= mutable.ArrayBuffer.fill(fileMetas.size)(JLong.valueOf(0L))
                lengths ++= fileMetas.map(file => JLong.valueOf(file.fileSize()))
                partitionColumns ++= mutable.ArrayBuffer.fill(fileMetas.size)(partitionRow)
                buckets ++= fileMetas.map(_ => JInteger.valueOf(bucket))
                firstRowIds ++= fileMetas
                  .map(_.firstRowId())
                  .map(id => JLong.valueOf(if (id == null) 0L else id.toLong))
                maxSequenceNumbers ++= fileMetas.map(
                  file => JLong.valueOf(file.maxSequenceNumber()))
                splitGroups ++= fileMetas.map(_ => JInteger.valueOf(splitIdx))
              case (other, _) =>
                throw new UnsupportedOperationException(
                  s"paimon split type: '${other.getClass.getName}' is not supported")
            }
          case other =>
            throw new GlutenNotSupportException(s"Unsupported input partition type: $other")
        }

        val preferredLoc =
          SoftAffinity.getFilePartitionLocations(paths.toArray, partition.preferredLocations())
        PaimonLocalFilesBuilder.makePaimonLocalFiles(
          partition.index,
          paths.asJava,
          starts.asJava,
          lengths.asJava,
          partitionColumns.asJava,
          fileFormat,
          preferredLoc.toList.asJava,
          new JHashMap[String, String](),
          buckets.asJava,
          firstRowIds.asJava,
          maxSequenceNumbers.asJava,
          splitGroups.asJava,
          useHiveSplit,
          primaryKeys,
          allRawConvertible
        )
      case (other, _) =>
        throw new GlutenNotSupportException(s"Unsupported input partition type: $other")
    }
  }

  override protected[this] def supportsBatchScan(scan: Scan): Boolean =
    AbstractPaimonScanTransformer.supportsBatchScan(scan).ok()
}

object AbstractPaimonScanTransformer {
  def apply(batchScan: BatchScanExec): PaimonScanTransformer = {
    PaimonScanTransformer(
      batchScan.output,
      batchScan.scan,
      batchScan.runtimeFilters,
      table = SparkShimLoader.getSparkShims.getBatchScanExecTable(batchScan),
      keyGroupedPartitioning = SparkShimLoader.getSparkShims.getKeyGroupedPartitioning(batchScan),
      commonPartitionValues = SparkShimLoader.getSparkShims.getCommonPartitionValues(batchScan)
    )
  }

  def supportsBatchScan(scan: Scan): ValidationResult = {
    if (!SQLConf.get.getConf(PaimonConfig.PAIMON_NATIVE_SOURCE_ENABLED)) {
      return ValidationResult.failed("[Paimon Fallback]: The paimon native source is not enabled.")
    }

    scan match {
      case paimonScan: PaimonScan =>
        val table = paimonScan.table
        val coreOptions = new CoreOptions(table.options())
        val partitionKeys = table.partitionKeys()
        val partitionType = table.rowType().project(partitionKeys)

        if (!table.isInstanceOf[FileStoreTable]) {
          return ValidationResult.failed(
            s"[Paimon Fallback]: The table is not fileStoreTable: ${table.getClass}")
        }

        if (partitionType.getFieldTypes.toArray.exists(_.isInstanceOf[DecimalType])) {
          return ValidationResult.failed(
            "[Paimon Fallback]: Not support decimal type as partition column")
        }

        val formatString = coreOptions.fileFormatString()
        if (
          !formatString.equalsIgnoreCase(CoreOptions.FILE_FORMAT_PARQUET) &&
          !formatString.equalsIgnoreCase(CoreOptions.FILE_FORMAT_ORC)
        ) {
          return ValidationResult.failed(
            "[Paimon Fallback]: Only support parquet/orc Paimon table.")
        }

        val isAllParquet = table
          .asInstanceOf[FileStoreTable]
          .coreOptions()
          .fileFormatPerLevel()
          .values()
          .stream()
          .allMatch(fmt => fmt.equalsIgnoreCase(CoreOptions.FILE_FORMAT_PARQUET)) ||
          paimonScan.coreOptions.fileFormatString().equalsIgnoreCase(
            CoreOptions.FILE_FORMAT_PARQUET)
        val schemaCols = scan.readSchema().fields.map(_.name).toSet
        val schemaHasMetadataCols = SUPPORTED_METADATA_COLUMNS.iterator.exists(schemaCols.contains)
        if (schemaHasMetadataCols && !isAllParquet) {
          return ValidationResult.failed(
            "[Paimon Fallback]: Metadata column queries are only supported with parquet files.")
        }

        val allSplitsRawConvertible = paimonScan.toBatch.planInputPartitions().forall {
          case partition: PaimonInputPartition =>
            partition.splits.forall(_.convertToRawFiles().isPresent)
          case _ => false
        }
        BackendsApiManager.getSparkPlanExecApiInstance.validatePaimonScanCapabilities(
          hasPrimaryKeys = !table.primaryKeys().isEmpty,
          allSplitsRawConvertible = allSplitsRawConvertible,
          deletionVectorsEnabled = coreOptions.deletionVectorsEnabled(),
          changelogProducer = coreOptions.changelogProducer().name(),
          mergeEngine = coreOptions.mergeEngine().name()
        )
      case other =>
        ValidationResult.failed(
          s"[Paimon Fallback] Scan is not a PaimonScan. Got ${other.getClass}")
    }
  }
}

case class PaimonScanTransformer(
    override val output: Seq[AttributeReference],
    @transient override val scan: Scan,
    override val runtimeFilters: Seq[Expression],
    @transient override val table: Table,
    override val keyGroupedPartitioning: Option[Seq[Expression]] = None,
    override val commonPartitionValues: Option[Seq[(InternalRow, Int)]] = None,
    override val pushDownFilters: Option[Seq[Expression]] = None)
  extends AbstractPaimonScanTransformer(
    output = output,
    scan = scan,
    runtimeFilters = runtimeFilters,
    table = table,
    keyGroupedPartitioning = keyGroupedPartitioning,
    commonPartitionValues = commonPartitionValues,
    pushDownFilters = pushDownFilters
  ) {

  override def withNewPushdownFilters(filters: Seq[Expression]): PaimonScanTransformer = {
    copy(pushDownFilters = Some(filters))
  }

  override def withOutput(newOutput: Seq[AttributeReference]): BatchScanExecTransformerBase = {
    copy(output = newOutput)
  }

  override def doCanonicalize(): PaimonScanTransformer = {
    copy(
      output = output.map(QueryPlan.normalizeExpressions(_, output)),
      runtimeFilters = QueryPlan.normalizePredicates(
        runtimeFilters.filterNot(_ == DynamicPruningExpression(Literal.TrueLiteral)),
        output),
      pushDownFilters = pushDownFilters.map(QueryPlan.normalizePredicates(_, output))
    )
  }
}
