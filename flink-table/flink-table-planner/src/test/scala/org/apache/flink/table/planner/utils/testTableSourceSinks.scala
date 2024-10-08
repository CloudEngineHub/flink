/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.utils

import org.apache.flink.api.common.io.InputFormat
import org.apache.flink.api.common.serialization.SerializerConfigImpl
import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation}
import org.apache.flink.api.common.typeutils.TypeSerializer
import org.apache.flink.api.java.io.{CollectionInputFormat, RowCsvInputFormat}
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.core.io.InputSplit
import org.apache.flink.legacy.table.factories.StreamTableSourceFactory
import org.apache.flink.legacy.table.sinks.StreamTableSink
import org.apache.flink.legacy.table.sources.{InputFormatTableSource, StreamTableSource}
import org.apache.flink.streaming.api.datastream.{DataStream, DataStreamSink}
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.{DataTypes, TableEnvironment}
import org.apache.flink.table.api.internal.TableEnvironmentInternal
import org.apache.flink.table.catalog._
import org.apache.flink.table.descriptors._
import org.apache.flink.table.descriptors.ConnectorDescriptorValidator.{CONNECTOR, CONNECTOR_TYPE}
import org.apache.flink.table.expressions.{CallExpression, Expression, FieldReferenceExpression, ValueLiteralExpression}
import org.apache.flink.table.expressions.ApiExpressionUtils.unresolvedCall
import org.apache.flink.table.functions.BuiltInFunctionDefinitions
import org.apache.flink.table.functions.BuiltInFunctionDefinitions.AND
import org.apache.flink.table.legacy.api.TableSchema
import org.apache.flink.table.legacy.descriptors.Schema
import org.apache.flink.table.legacy.factories.{TableSinkFactory, TableSourceFactory}
import org.apache.flink.table.legacy.sinks.TableSink
import org.apache.flink.table.legacy.sources._
import org.apache.flink.table.legacy.sources.tsextractors.ExistingField
import org.apache.flink.table.planner._
import org.apache.flink.table.planner.plan.hint.OptionsHintTest.IS_BOUNDED
import org.apache.flink.table.planner.runtime.utils.BatchTestBase.row
import org.apache.flink.table.planner.runtime.utils.TimeTestUtil.EventTimeSourceFunction
import org.apache.flink.table.runtime.types.TypeInfoDataTypeConverter.fromDataTypeToTypeInfo
import org.apache.flink.table.sinks.CsvBatchTableSinkFactory
import org.apache.flink.table.sources.wmstrategies.{AscendingTimestamps, PreserveWatermarks}
import org.apache.flink.table.types.DataType
import org.apache.flink.table.utils.EncodingUtils
import org.apache.flink.table.utils.TableSchemaUtils.getPhysicalSchema
import org.apache.flink.types.Row

import _root_.java.io.{File, FileOutputStream, OutputStreamWriter}
import _root_.java.util
import _root_.java.util.Collections
import _root_.java.util.function.BiConsumer
import _root_.scala.collection.JavaConversions._
import _root_.scala.collection.JavaConverters._
import _root_.scala.collection.mutable

object TestTableSourceSinks {
  def createPersonCsvTemporaryTable(tEnv: TableEnvironment, tableName: String): Unit = {
    tEnv.executeSql(s"""
                       |CREATE TEMPORARY TABLE $tableName (
                       |  first STRING,
                       |  id INT,
                       |  score DOUBLE,
                       |  last STRING
                       |) WITH (
                       |  'connector.type' = 'filesystem',
                       |  'connector.path' = '$getPersonCsvPath',
                       |  'format.type' = 'csv',
                       |  'format.field-delimiter' = '#',
                       |  'format.line-delimiter' = '$$',
                       |  'format.ignore-first-line' = 'true',
                       |  'format.comment-prefix' = '%'
                       |)
                       |""".stripMargin)
  }

  def createOrdersCsvTemporaryTable(tEnv: TableEnvironment, tableName: String): Unit = {
    tEnv.executeSql(s"""
                       |CREATE TEMPORARY TABLE $tableName (
                       |  amount BIGINT,
                       |  currency STRING,
                       |  ts BIGINT
                       |) WITH (
                       |  'connector.type' = 'filesystem',
                       |  'connector.path' = '$getOrdersCsvPath',
                       |  'format.type' = 'csv',
                       |  'format.field-delimiter' = ',',
                       |  'format.line-delimiter' = '$$'
                       |)
                       |""".stripMargin)
  }

  def createRatesCsvTemporaryTable(tEnv: TableEnvironment, tableName: String): Unit = {
    tEnv.executeSql(s"""
                       |CREATE TEMPORARY TABLE $tableName (
                       |  currency STRING,
                       |  rate BIGINT
                       |) WITH (
                       |  'connector.type' = 'filesystem',
                       |  'connector.path' = '$getRatesCsvPath',
                       |  'format.type' = 'csv',
                       |  'format.field-delimiter' = ',',
                       |  'format.line-delimiter' = '$$'
                       |)
                       |""".stripMargin)
  }

  def createCsvTemporarySinkTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      numFiles: Int = 1): String = {
    val tempFile = File.createTempFile("csv-test", null)
    tempFile.deleteOnExit()
    val path = tempFile.getAbsolutePath

    val sinkOptions = collection.mutable.Map(
      "connector.type" -> "filesystem",
      "connector.path" -> path,
      "format.type" -> "csv",
      "format.write-mode" -> "OVERWRITE",
      "format.num-files" -> numFiles.toString
    )
    sinkOptions.putAll(new Schema().schema(schema).toProperties)

    val sink = new CsvBatchTableSinkFactory().createStreamTableSink(sinkOptions);
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSinkInternal(tableName, sink)

    path
  }

  lazy val getPersonCsvPath = {
    val csvRecords = Seq(
      "First#Id#Score#Last",
      "Mike#1#12.3#Smith",
      "Bob#2#45.6#Taylor",
      "Sam#3#7.89#Miller",
      "Peter#4#0.12#Smith",
      "% Just a comment",
      "Liz#5#34.5#Williams",
      "Sally#6#6.78#Miller",
      "Alice#7#90.1#Smith",
      "Kelly#8#2.34#Williams"
    )

    writeToTempFile(csvRecords.mkString("$"), "csv-test", "tmp")
  }

  lazy val getOrdersCsvPath = {
    val csvRecords = Seq(
      "2,Euro,2",
      "1,US Dollar,3",
      "50,Yen,4",
      "3,Euro,5",
      "5,US Dollar,6"
    )

    writeToTempFile(csvRecords.mkString("$"), "csv-order-test", "tmp")
  }

  lazy val getRatesCsvPath = {
    val csvRecords = Seq(
      "US Dollar,102",
      "Yen,1",
      "Euro,119",
      "RMB,702"
    )
    writeToTempFile(csvRecords.mkString("$"), "csv-rate-test", "tmp")

  }

  private def writeToTempFile(
      contents: String,
      filePrefix: String,
      fileSuffix: String,
      charset: String = "UTF-8"): String = {
    val tempFile = File.createTempFile(filePrefix, fileSuffix)
    tempFile.deleteOnExit()
    val tmpWriter = new OutputStreamWriter(new FileOutputStream(tempFile), charset)
    tmpWriter.write(contents)
    tmpWriter.close()
    tempFile.getAbsolutePath
  }
}

class TestPreserveWMTableSource[T](
    tableSchema: TableSchema,
    returnType: TypeInformation[T],
    values: Seq[Either[(Long, T), Long]],
    rowtime: String)
  extends StreamTableSource[T]
  with DefinedRowtimeAttributes {

  override def getRowtimeAttributeDescriptors: util.List[RowtimeAttributeDescriptor] = {
    Collections.singletonList(
      new RowtimeAttributeDescriptor(
        rowtime,
        new ExistingField(rowtime),
        PreserveWatermarks.INSTANCE))
  }

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[T] = {
    execEnv
      .addSource(new EventTimeSourceFunction[T](values))
      .setParallelism(1)
      .setMaxParallelism(1)
      .returns(returnType)
  }

  override def getReturnType: TypeInformation[T] = returnType

  override def getTableSchema: TableSchema = tableSchema

  override def explainSource(): String = ""

}

/**
 * A data source that implements some very basic filtering in-memory in order to test expression
 * push-down logic.
 *
 * <p>NOTE: Currently, only `>, >=, &lt;, <=, =, &lt;>` operators and UPPER and LOWER functions are
 * allowed to be pushed down into this source.
 *
 * @param isBounded
 *   whether this is a bounded source
 * @param schema
 *   The TableSchema for the source.
 * @param data
 *   The data that filtering is applied to in order to get the final dataset.
 * @param filterableFields
 *   The fields that are allowed to be filtered.
 * @param filterPredicates
 *   The predicates that should be used to filter.
 * @param filterPushedDown
 *   Whether predicates have been pushed down yet.
 */
class TestLegacyFilterableTableSource(
    override val isBounded: Boolean,
    schema: TableSchema,
    data: Seq[Row],
    filterableFields: Set[String] = Set(),
    filterPredicates: Seq[Expression] = Seq(),
    val filterPushedDown: Boolean = false,
    val numElementToSkip: Int = -1)
  extends StreamTableSource[Row]
  with FilterableTableSource[Row] {

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
    val records = if (numElementToSkip > 0) {
      if (numElementToSkip >= data.size) {
        Seq.empty[Row]
      } else {
        data.slice(numElementToSkip, data.size)
      }
    } else {
      data
    }

    execEnv
      .fromData[Row](
        applyPredicatesToRows(records).asJava,
        fromDataTypeToTypeInfo(getProducedDataType).asInstanceOf[RowTypeInfo])
      .setParallelism(1)
      .setMaxParallelism(1)
  }

  override def explainSource(): String = {
    if (filterPredicates.nonEmpty) {
      s"filterPushedDown=[$filterPushedDown], " +
        s"filter=[${filterPredicates.reduce((l, r) => unresolvedCall(AND, l, r)).toString}]"
    } else {
      s"filterPushedDown=[$filterPushedDown], filter=[]"
    }
  }

  override def getProducedDataType: DataType = schema.toRowDataType

  override def applyPredicate(predicates: JList[Expression]): TableSource[Row] = {
    val predicatesToUse = new mutable.ListBuffer[Expression]()
    val iterator = predicates.iterator()
    while (iterator.hasNext) {
      val expr = iterator.next()
      if (shouldPushDown(expr)) {
        predicatesToUse += expr
        iterator.remove()
      }
    }

    new TestLegacyFilterableTableSource(
      isBounded,
      schema,
      data,
      filterableFields,
      predicatesToUse,
      filterPushedDown = true)
  }

  override def isFilterPushedDown: Boolean = filterPushedDown

  private def applyPredicatesToRows(rows: Seq[Row]): Seq[Row] = rows.filter(shouldKeep)

  private def shouldPushDown(expr: Expression): Boolean = {
    expr match {
      case expr: CallExpression if expr.getChildren.size() == 2 =>
        shouldPushDownUnaryExpression(expr.getChildren.head) &&
        shouldPushDownUnaryExpression(expr.getChildren.last)
      case _ => false
    }
  }

  private def shouldPushDownUnaryExpression(expr: Expression): Boolean = expr match {
    case f: FieldReferenceExpression => filterableFields.contains(f.getName)
    case _: ValueLiteralExpression => true
    case c: CallExpression if c.getChildren.size() == 1 =>
      c.getFunctionDefinition match {
        case BuiltInFunctionDefinitions.UPPER | BuiltInFunctionDefinitions.LOWER =>
          shouldPushDownUnaryExpression(c.getChildren.head)
        case _ => false
      }
    case _ => false
  }

  private def shouldKeep(row: Row): Boolean = {
    filterPredicates.isEmpty || filterPredicates.forall {
      case expr: CallExpression if expr.getChildren.size() == 2 =>
        binaryFilterApplies(expr, row)
      case expr => throw new RuntimeException(expr + " not supported!")
    }
  }

  private def binaryFilterApplies(binExpr: CallExpression, row: Row): Boolean = {
    val children = binExpr.getChildren
    require(children.size() == 2)
    val (lhsValue, rhsValue) = extractValues(binExpr, row)

    binExpr.getFunctionDefinition match {
      case BuiltInFunctionDefinitions.GREATER_THAN =>
        lhsValue.compareTo(rhsValue) > 0
      case BuiltInFunctionDefinitions.LESS_THAN =>
        lhsValue.compareTo(rhsValue) < 0
      case BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL =>
        lhsValue.compareTo(rhsValue) >= 0
      case BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL =>
        lhsValue.compareTo(rhsValue) <= 0
      case BuiltInFunctionDefinitions.EQUALS =>
        lhsValue.compareTo(rhsValue) == 0
      case BuiltInFunctionDefinitions.NOT_EQUALS =>
        lhsValue.compareTo(rhsValue) != 0
    }
  }

  private def extractValues(
      binExpr: CallExpression,
      row: Row): (Comparable[Any], Comparable[Any]) = {
    val children = binExpr.getChildren
    require(children.size() == 2)
    (getValue(children.head, row), getValue(children.last, row))
  }

  private def getValue(expr: Expression, row: Row): Comparable[Any] = expr match {
    case v: ValueLiteralExpression =>
      val value = v.getValueAs(v.getOutputDataType.getConversionClass)
      if (value.isPresent) {
        value.get().asInstanceOf[Comparable[Any]]
      } else {
        null
      }
    case f: FieldReferenceExpression =>
      val rowTypeInfo = schema.toRowType.asInstanceOf[RowTypeInfo]
      val idx = rowTypeInfo.getFieldIndex(f.getName)
      row.getField(idx).asInstanceOf[Comparable[Any]]
    case c: CallExpression if c.getChildren.size() == 1 =>
      val child = getValue(c.getChildren.head, row)
      c.getFunctionDefinition match {
        case BuiltInFunctionDefinitions.UPPER =>
          child.toString.toUpperCase.asInstanceOf[Comparable[Any]]
        case BuiltInFunctionDefinitions.LOWER =>
          child.toString.toLowerCase().asInstanceOf[Comparable[Any]]
        case _ => throw new RuntimeException(c + " not supported!")
      }
    case _ => throw new RuntimeException(expr + " not supported!")
  }

  override def getTableSchema: TableSchema = schema
}

object TestLegacyFilterableTableSource {
  val defaultFilterableFields = Set("amount")

  val defaultSchema: TableSchema = TableSchema
    .builder()
    .field("name", DataTypes.STRING)
    .field("id", DataTypes.BIGINT)
    .field("amount", DataTypes.INT)
    .field("price", DataTypes.DOUBLE)
    .build()

  val defaultRows: Seq[Row] = {
    for {
      cnt <- 0 until 33
    } yield {
      Row.of(
        s"Record_$cnt",
        cnt.toLong.asInstanceOf[AnyRef],
        cnt.toInt.asInstanceOf[AnyRef],
        cnt.toDouble.asInstanceOf[AnyRef])
    }
  }

  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      isBounded: Boolean = false,
      data: List[Row] = TestLegacyFilterableTableSource.defaultRows.toList,
      filterableFields: Set[String] = TestLegacyFilterableTableSource.defaultFilterableFields)
      : Unit = {
    val source = new TestLegacyFilterableTableSource(isBounded, schema, data, filterableFields)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

/** Table source factory to find and create [[TestLegacyFilterableTableSource]]. */
class TestLegacyFilterableTableSourceFactory extends StreamTableSourceFactory[Row] {
  override def createStreamTableSource(properties: JMap[String, String]): StreamTableSource[Row] = {
    val descriptorProps = new DescriptorProperties()
    descriptorProps.putProperties(properties)
    val isBounded = descriptorProps.getOptionalBoolean("is-bounded").orElse(false)
    val schema = descriptorProps.getTableSchema(Schema.SCHEMA)
    val serializedRows = descriptorProps.getOptionalString("data").orElse(null)
    val numElementToSkip: Int =
      descriptorProps.getOptionalInt("source.num-element-to-skip").orElse(-1)
    val rows = if (serializedRows != null) {
      EncodingUtils.decodeStringToObject(serializedRows, classOf[List[Row]])
    } else {
      TestLegacyFilterableTableSource.defaultRows
    }
    val serializedFilterableFields =
      descriptorProps.getOptionalString("filterable-fields").orElse(null)
    val filterableFields = if (serializedFilterableFields != null) {
      EncodingUtils.decodeStringToObject(serializedFilterableFields, classOf[List[String]]).toSet
    } else {
      TestLegacyFilterableTableSource.defaultFilterableFields
    }
    new TestLegacyFilterableTableSource(
      isBounded,
      schema,
      rows,
      filterableFields,
      numElementToSkip = numElementToSkip)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestFilterableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new JArrayList[String]()
    supported.add("*")
    supported
  }
}

class TestInputFormatTableSource[T](tableSchema: TableSchema, values: Seq[T])
  extends InputFormatTableSource[T] {

  override def getInputFormat: InputFormat[T, _ <: InputSplit] = {
    val returnType = tableSchema.toRowType.asInstanceOf[TypeInformation[T]]
    new CollectionInputFormat[T](
      values.asJava,
      returnType.createSerializer(new SerializerConfigImpl))
  }

  override def getReturnType: TypeInformation[T] =
    throw new RuntimeException("Should not invoke this deprecated method.")

  override def getProducedDataType: DataType = tableSchema.toRowDataType

  override def getTableSchema: TableSchema = tableSchema
}

object TestInputFormatTableSource {
  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      data: Seq[_],
      tableName: String): Unit = {
    val source = new TestInputFormatTableSource(schema, data)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class TestInputFormatTableSourceFactory[T] extends StreamTableSourceFactory[T] {
  override def createStreamTableSource(properties: JMap[String, String]): StreamTableSource[T] = {
    val descriptorProps = new DescriptorProperties()
    descriptorProps.putProperties(properties)
    val schema = descriptorProps.getTableSchema(Schema.SCHEMA)
    val serializedRows = descriptorProps.getOptionalString("data").orElse(null)
    val values = if (serializedRows != null) {
      EncodingUtils.decodeStringToObject(serializedRows, classOf[List[T]])
    } else {
      Seq.empty[T]
    }
    new TestInputFormatTableSource[T](schema, values)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestInputFormatTableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new JArrayList[String]()
    supported.add("*")
    supported
  }
}

class TestDataTypeTableSource(tableSchema: TableSchema, values: Seq[Row])
  extends InputFormatTableSource[Row] {

  override def getInputFormat: InputFormat[Row, _ <: InputSplit] = {
    new CollectionInputFormat[Row](
      values.asJava,
      fromDataTypeToTypeInfo(getProducedDataType)
        .createSerializer(new SerializerConfigImpl)
        .asInstanceOf[TypeSerializer[Row]])
  }

  override def getReturnType: TypeInformation[Row] =
    throw new RuntimeException("Should not invoke this deprecated method.")

  override def getProducedDataType: DataType = tableSchema.toRowDataType

  override def getTableSchema: TableSchema = tableSchema
}

class TestDataTypeTableSourceFactory extends TableSourceFactory[Row] {

  override def createTableSource(properties: JMap[String, String]): TableSource[Row] = {
    val descriptorProperties = new DescriptorProperties
    descriptorProperties.putProperties(properties)
    val tableSchema = getPhysicalSchema(descriptorProperties.getTableSchema(Schema.SCHEMA))
    val serializedRows = descriptorProperties.getOptionalString("data").orElse(null)
    val data = if (serializedRows != null) {
      EncodingUtils.decodeStringToObject(serializedRows, classOf[List[Row]])
    } else {
      Seq.empty[Row]
    }
    new TestDataTypeTableSource(tableSchema, data)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestDataTypeTableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new util.ArrayList[String]()
    supported.add("*")
    supported
  }
}

object TestDataTypeTableSource {
  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      data: Seq[Row]): Unit = {
    val source = new TestDataTypeTableSource(schema, data)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class TestDataTypeTableSourceWithTime(
    tableSchema: TableSchema,
    values: Seq[Row],
    rowtime: String = null)
  extends InputFormatTableSource[Row]
  with DefinedRowtimeAttributes {

  override def getInputFormat: InputFormat[Row, _ <: InputSplit] = {
    new CollectionInputFormat[Row](
      values.asJava,
      fromDataTypeToTypeInfo(getProducedDataType)
        .createSerializer(new SerializerConfigImpl)
        .asInstanceOf[TypeSerializer[Row]])
  }

  override def getReturnType: TypeInformation[Row] =
    throw new RuntimeException("Should not invoke this deprecated method.")

  override def getProducedDataType: DataType = tableSchema.toRowDataType

  override def getTableSchema: TableSchema = tableSchema

  override def getRowtimeAttributeDescriptors: JList[RowtimeAttributeDescriptor] = {
    // return a RowtimeAttributeDescriptor if rowtime attribute is defined
    if (rowtime != null) {
      Collections.singletonList(
        new RowtimeAttributeDescriptor(
          rowtime,
          new ExistingField(rowtime),
          new AscendingTimestamps))
    } else {
      Collections.EMPTY_LIST.asInstanceOf[JList[RowtimeAttributeDescriptor]]
    }
  }
}

class TestDataTypeTableSourceWithTimeFactory extends TableSourceFactory[Row] {

  override def createTableSource(properties: JMap[String, String]): TableSource[Row] = {
    val descriptorProperties = new DescriptorProperties
    descriptorProperties.putProperties(properties)
    val tableSchema = getPhysicalSchema(descriptorProperties.getTableSchema(Schema.SCHEMA))

    val serializedRows = descriptorProperties.getOptionalString("data").orElse(null)
    val data = if (serializedRows != null) {
      EncodingUtils.decodeStringToObject(serializedRows, classOf[List[Row]])
    } else {
      Seq.empty[Row]
    }

    val rowTime = descriptorProperties.getOptionalString("rowtime").orElse(null)
    new TestDataTypeTableSourceWithTime(tableSchema, data, rowTime)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestDataTypeTableSourceWithTime")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new util.ArrayList[String]()
    supported.add("*")
    supported
  }
}

object TestDataTypeTableSourceWithTime {
  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      data: Seq[Row],
      rowTime: String): Unit = {
    val source = new TestDataTypeTableSourceWithTime(schema, data, rowTime)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class TestStreamTableSource(tableSchema: TableSchema, values: Seq[Row])
  extends StreamTableSource[Row] {

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
    execEnv.fromData(values.asJava, tableSchema.toRowType)
  }

  override def getProducedDataType: DataType = tableSchema.toRowDataType

  override def getTableSchema: TableSchema = tableSchema
}

object TestStreamTableSource {
  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      data: Seq[Row] = null): Unit = {
    val source = new TestStreamTableSource(schema, data)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class TestStreamTableSourceFactory extends StreamTableSourceFactory[Row] {
  override def createStreamTableSource(properties: JMap[String, String]): StreamTableSource[Row] = {
    val descriptorProperties = new DescriptorProperties
    descriptorProperties.putProperties(properties)
    val tableSchema = descriptorProperties.getTableSchema(Schema.SCHEMA)
    val serializedRows = descriptorProperties.getOptionalString("data").orElse(null)
    val values = if (serializedRows != null) {
      EncodingUtils.decodeStringToObject(serializedRows, classOf[List[Row]])
    } else {
      Seq.empty[Row]
    }
    new TestStreamTableSource(tableSchema, values)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestStreamTableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new util.ArrayList[String]()
    supported.add("*")
    supported
  }
}

class TestFileInputFormatTableSource(paths: Array[String], tableSchema: TableSchema)
  extends InputFormatTableSource[Row] {

  override def getInputFormat: InputFormat[Row, _ <: InputSplit] = {
    val format = new RowCsvInputFormat(null, tableSchema.getFieldTypes)
    format.setFilePaths(paths: _*)
    format
  }

  override def getProducedDataType: DataType = tableSchema.toRowDataType

  override def getTableSchema: TableSchema = tableSchema
}

object TestFileInputFormatTableSource {
  def createTemporaryTable(
      tEnv: TableEnvironment,
      schema: TableSchema,
      tableName: String,
      path: Array[String]): Unit = {
    val source = new TestFileInputFormatTableSource(path, schema)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class TestFileInputFormatTableSourceFactory extends StreamTableSourceFactory[Row] {

  override def createStreamTableSource(properties: JMap[String, String]): StreamTableSource[Row] = {
    val descriptorProperties = new DescriptorProperties
    descriptorProperties.putProperties(properties)
    val tableSchema = descriptorProperties.getTableSchema(Schema.SCHEMA)

    val serializedPaths = descriptorProperties.getOptionalString("path").orElse(null)
    val paths = if (serializedPaths != null) {
      EncodingUtils.decodeStringToObject(serializedPaths, classOf[Array[String]])
    } else {
      Array.empty[String]
    }
    new TestFileInputFormatTableSource(paths, tableSchema)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestFileInputFormatTableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val supported = new util.ArrayList[String]()
    supported.add("*")
    supported
  }
}

/**
 * A data source that implements some very basic partitionable table source in-memory.
 *
 * @param isBounded
 *   whether this is a bounded source
 * @param remainingPartitions
 *   remaining partitions after partition pruning
 */
class TestPartitionableTableSource(
    override val isBounded: Boolean,
    remainingPartitions: JList[JMap[String, String]],
    isCatalogTable: Boolean)
  extends StreamTableSource[Row]
  with PartitionableTableSource {

  private val fieldTypes: Array[TypeInformation[_]] = Array(
    BasicTypeInfo.INT_TYPE_INFO,
    BasicTypeInfo.STRING_TYPE_INFO,
    BasicTypeInfo.STRING_TYPE_INFO,
    BasicTypeInfo.INT_TYPE_INFO)
  // 'part1' and 'part2' are partition fields
  private val fieldNames = Array("id", "name", "part1", "part2")
  private val returnType = new RowTypeInfo(fieldTypes, fieldNames)

  private val data = mutable.Map[String, Seq[Row]](
    "part1=A,part2=1" -> Seq(row(1, "Anna", "A", 1), row(2, "Jack", "A", 1)),
    "part1=A,part2=2" -> Seq(row(3, "John", "A", 2), row(4, "nosharp", "A", 2)),
    "part1=B,part2=3" -> Seq(row(5, "Peter", "B", 3), row(6, "Lucy", "B", 3)),
    "part1=C,part2=1" -> Seq(row(7, "He", "C", 1), row(8, "Le", "C", 1))
  )

  override def getPartitions: JList[JMap[String, String]] = {
    if (isCatalogTable) {
      throw new RuntimeException("Should not expected.")
    }
    List(
      Map("part1" -> "A", "part2" -> "1").asJava,
      Map("part1" -> "A", "part2" -> "2").asJava,
      Map("part1" -> "B", "part2" -> "3").asJava,
      Map("part1" -> "C", "part2" -> "1").asJava
    ).asJava
  }

  override def applyPartitionPruning(
      remainingPartitions: JList[JMap[String, String]]): TableSource[_] = {
    new TestPartitionableTableSource(isBounded, remainingPartitions, isCatalogTable)
  }

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
    val remainingData = if (remainingPartitions != null) {
      val remainingPartitionList = remainingPartitions.map {
        m => s"part1=${m.get("part1")},part2=${m.get("part2")}"
      }
      data.filterKeys(remainingPartitionList.contains).values.flatten
    } else {
      data.values.flatten
    }

    execEnv.fromData[Row](remainingData, getReturnType).setParallelism(1).setMaxParallelism(1)
  }

  override def explainSource(): String = {
    if (remainingPartitions != null) {
      s"partitions=${remainingPartitions.mkString(", ")}"
    } else {
      ""
    }
  }

  override def getReturnType: TypeInformation[Row] = returnType

  override def getTableSchema: TableSchema = new TableSchema(fieldNames, fieldTypes)
}

class TestPartitionableSourceFactory extends TableSourceFactory[Row] {

  override def requiredContext(): util.Map[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestPartitionableSource")
    context
  }

  override def supportedProperties(): util.List[String] = {
    val supported = new util.ArrayList[String]()
    supported.add("*")
    supported
  }

  override def createTableSource(properties: util.Map[String, String]): TableSource[Row] = {
    val dp = new DescriptorProperties()
    dp.putProperties(properties)

    val isBounded = dp.getBoolean("is-bounded")
    val sourceFetchPartitions = dp.getBoolean("source-fetch-partitions")
    val remainingPartitions = dp
      .getOptionalArray(
        "remaining-partition",
        new java.util.function.Function[String, util.Map[String, String]] {
          override def apply(t: String): util.Map[String, String] = {
            dp.getString(t)
              .split(",")
              .map(kv => kv.split(":"))
              .map(a => (a(0), a(1)))
              .toMap[String, String]
          }
        }
      )
      .orElse(null)
    new TestPartitionableTableSource(isBounded, remainingPartitions, sourceFetchPartitions)
  }
}

object TestPartitionableSourceFactory {
  private val tableSchema: org.apache.flink.table.api.Schema = org.apache.flink.table.api.Schema
    .newBuilder()
    .fromResolvedSchema(ResolvedSchema.of(
      Column.physical("id", DataTypes.INT()),
      Column.physical("name", DataTypes.STRING()),
      Column.physical("part1", DataTypes.STRING()),
      Column.physical("part2", DataTypes.INT())
    ))
    .build()

  /** For java invoking. */
  def createTemporaryTable(tEnv: TableEnvironment, tableName: String, isBounded: Boolean): Unit = {
    createTemporaryTable(tEnv, tableName, isBounded, tableSchema = tableSchema)
  }

  def createTemporaryTable(
      tEnv: TableEnvironment,
      tableName: String,
      isBounded: Boolean,
      tableSchema: org.apache.flink.table.api.Schema = tableSchema,
      remainingPartitions: JList[JMap[String, String]] = null,
      sourceFetchPartitions: Boolean = false): Unit = {
    val properties = new DescriptorProperties()
    properties.putString("is-bounded", isBounded.toString)
    properties.putBoolean("source-fetch-partitions", sourceFetchPartitions)
    properties.putString(CONNECTOR_TYPE, "TestPartitionableSource")
    if (remainingPartitions != null) {
      remainingPartitions.zipWithIndex.foreach {
        case (part, i) =>
          properties.putString(
            "remaining-partition." + i,
            part.map { case (k, v) => s"$k:$v" }.reduce((kv1, kv2) => s"$kv1,:$kv2")
          )
      }
    }

    val table = CatalogTable.of(
      tableSchema,
      "",
      util.Arrays.asList[String]("part1", "part2"),
      properties.asMap()
    )
    val catalog = tEnv.getCatalog(tEnv.getCurrentCatalog).get()
    val path = new ObjectPath(tEnv.getCurrentDatabase, tableName)
    catalog.createTable(path, table, false)

    val partitions = List(
      Map("part1" -> "A", "part2" -> "1").asJava,
      Map("part1" -> "A", "part2" -> "2").asJava,
      Map("part1" -> "B", "part2" -> "3").asJava,
      Map("part1" -> "C", "part2" -> "1").asJava
    )
    partitions.foreach(
      spec =>
        catalog.createPartition(
          path,
          new CatalogPartitionSpec(new java.util.LinkedHashMap(spec)),
          new CatalogPartitionImpl(Map[String, String](), ""),
          true))

  }
}

/**
 * Used for stream/TableScanITCase#testTableSourceWithoutTimeAttribute or
 * batch/TableScanITCase#testTableSourceWithoutTimeAttribute
 */
class WithoutTimeAttributesTableSource(bounded: Boolean) extends StreamTableSource[Row] {

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
    val data = Seq(
      Row.of("Mary", new JLong(1L), new JInt(1)),
      Row.of("Bob", new JLong(2L), new JInt(3))
    )
    val dataStream =
      execEnv
        .fromData(data.asJava)
        .returns(fromDataTypeToTypeInfo(getProducedDataType).asInstanceOf[RowTypeInfo])
    dataStream.getTransformation.setMaxParallelism(1)
    dataStream
  }

  override def isBounded: Boolean = bounded

  override def getProducedDataType: DataType = {
    WithoutTimeAttributesTableSource.tableSchema.toRowDataType
  }

  override def getTableSchema: TableSchema = WithoutTimeAttributesTableSource.tableSchema
}

object WithoutTimeAttributesTableSource {
  lazy val tableSchema = TableSchema
    .builder()
    .field("name", DataTypes.STRING())
    .field("id", DataTypes.BIGINT())
    .field("value", DataTypes.INT())
    .build()

  def createTemporaryTable(tEnv: TableEnvironment, tableName: String): Unit = {
    val source = new WithoutTimeAttributesTableSource(true)
    tEnv.asInstanceOf[TableEnvironmentInternal].registerTableSourceInternal(tableName, source)
  }
}

class WithoutTimeAttributesTableSourceFactory extends TableSourceFactory[Row] {
  override def createTableSource(properties: JMap[String, String]): TableSource[Row] = {
    val dp = new DescriptorProperties
    dp.putProperties(properties)
    val isBounded = dp.getOptionalBoolean("is-bounded").orElse(false)
    new WithoutTimeAttributesTableSource(isBounded)
  }

  override def requiredContext(): JMap[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "WithoutTimeAttributesTableSource")
    context
  }

  override def supportedProperties(): JList[String] = {
    val properties = new util.ArrayList[String]()
    properties.add("*")
    properties
  }
}

/** Factory for [[OptionsTableSource]]. */
class TestOptionsTableFactory extends TableSourceFactory[Row] with TableSinkFactory[Row] {
  import TestOptionsTableFactory._

  override def requiredContext(): util.Map[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR, "OPTIONS")
    context
  }

  override def supportedProperties(): util.List[String] = {
    val supported = new JArrayList[String]()
    supported.add("*")
    supported
  }

  override def createTableSource(context: TableSourceFactory.Context): TableSource[Row] = {
    createPropertiesSource(context.getTable.toProperties)
  }

  override def createTableSink(context: TableSinkFactory.Context): TableSink[Row] = {
    createPropertiesSink(context.getTable.toProperties)
  }
}

/** A table source that explains the properties in the plan. */
class OptionsTableSource(isBounded: Boolean, tableSchema: TableSchema, props: JMap[String, String])
  extends StreamTableSource[Row] {

  override def explainSource(): String = s"${classOf[OptionsTableSource].getSimpleName}" +
    s"(props=$props)"

  override def getTableSchema: TableSchema = getPhysicalSchema(tableSchema)

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] =
    None.asInstanceOf[DataStream[Row]]

  override def isBounded: Boolean = {
    isBounded
  }
}

/** A table source that explains the properties in the plan. */
class OptionsTableSink(tableSchema: TableSchema, val props: JMap[String, String])
  extends StreamTableSink[Row] {

  override def consumeDataStream(dataStream: DataStream[Row]): DataStreamSink[_] = {
    None.asInstanceOf[DataStreamSink[Row]]
  }

  override def configure(
      fieldNames: Array[String],
      fieldTypes: Array[TypeInformation[_]]): TableSink[Row] = this

  override def getTableSchema: TableSchema = tableSchema

  override def getConsumedDataType: DataType = {
    getPhysicalSchema(tableSchema).toRowDataType
  }
}

object TestOptionsTableFactory {

  def createPropertiesSource(props: JMap[String, String]): OptionsTableSource = {
    val properties = new DescriptorProperties()
    properties.putProperties(props)
    val schema = properties.getTableSchema(Schema.SCHEMA)
    val propsToShow = new util.HashMap[String, String]()
    val isBounded = properties.getBoolean(IS_BOUNDED)
    props.forEach(new BiConsumer[String, String] {
      override def accept(k: String, v: String): Unit = {
        if (
          !k.startsWith(Schema.SCHEMA)
          && !k.equalsIgnoreCase(CONNECTOR)
          && !k.equalsIgnoreCase(IS_BOUNDED)
        ) {
          propsToShow.put(k, v)
        }
      }
    })

    new OptionsTableSource(isBounded, schema, propsToShow)
  }

  def createPropertiesSink(props: JMap[String, String]): OptionsTableSink = {
    val properties = new DescriptorProperties()
    properties.putProperties(props)
    val schema = properties.getTableSchema(Schema.SCHEMA)
    val propsToShow = new util.HashMap[String, String]()
    props.forEach(new BiConsumer[String, String] {
      override def accept(k: String, v: String): Unit = {
        if (
          !k.startsWith(Schema.SCHEMA)
          && !k.equalsIgnoreCase(CONNECTOR)
          && !k.equalsIgnoreCase(IS_BOUNDED)
        ) {
          propsToShow.put(k, v)
        }
      }
    })

    new OptionsTableSink(schema, propsToShow)
  }
}
