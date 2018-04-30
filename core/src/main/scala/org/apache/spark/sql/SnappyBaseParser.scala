/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql

import java.util.concurrent.ConcurrentHashMap

import com.gemstone.gemfire.internal.shared.SystemProperties
import io.snappydata.Constant
import io.snappydata.collection.OpenHashSet
import org.parboiled2._

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SnappyParserConsts => Consts}

/**
 * Base parsing facilities for all SnappyData SQL parsers.
 */
abstract class SnappyBaseParser(session: SparkSession) extends Parser {

  protected var caseSensitive: Boolean = session.sessionState.conf.caseSensitiveAnalysis

  private[sql] final val queryHints: ConcurrentHashMap[String, String] =
    new ConcurrentHashMap[String, String](4, 0.7f, 1)

  protected def reset(): Unit = queryHints.clear()

  protected final def commentBody: Rule0 = rule {
    "*/" | ANY ~ commentBody
  }

  protected final def commentBodyOrHint: Rule0 = rule {
    '+' ~ (Consts.whitespace.* ~ capture(CharPredicate.Alpha ~
        Consts.identifier.*) ~ Consts.whitespace.* ~
        '(' ~ capture(noneOf(Consts.hintValueEnd).*) ~ ')' ~>
        ((k: String, v: String) => queryHints.put(k, v.trim): Unit)). + ~
        commentBody |
    commentBody
  }

  protected final def lineCommentOrHint: Rule0 = rule {
    '+' ~ (Consts.space.* ~ capture(CharPredicate.Alpha ~
        Consts.identifier.*) ~ Consts.space.* ~
        '(' ~ capture(noneOf(Consts.lineHintEnd).*) ~ ')' ~>
        ((k: String, v: String) => queryHints.put(k, v.trim): Unit)). + ~
        noneOf(Consts.lineCommentEnd).* |
    noneOf(Consts.lineCommentEnd).*
  }

  /** The recognized whitespace characters and comments. */
  protected final def ws: Rule0 = rule {
    quiet(
      Consts.whitespace |
      '-' ~ '-' ~ lineCommentOrHint |
      '/' ~ '*' ~ (commentBodyOrHint | fail("unclosed comment"))
    ).*
  }

  /** All recognized delimiters including whitespace. */
  final def delimiter: Rule0 = rule {
    quiet((Consts.whitespace ~ ws) | &(Consts.delimiters)) | EOI
  }

  protected final def commaSep: Rule0 = rule {
    ',' ~ ws
  }

  protected final def questionMark: Rule0 = rule {
    '?' ~ ws
  }

  protected final def digits: Rule1[String] = rule {
    capture(CharPredicate.Digit. +) ~ ws
  }

  protected final def integral: Rule1[String] = rule {
    capture(Consts.plusOrMinus.? ~ CharPredicate.Digit. +) ~ ws
  }

  protected final def scientificNotation: Rule0 = rule {
    Consts.exponent ~ Consts.plusOrMinus.? ~ CharPredicate.Digit. +
  }

  protected final def stringLiteral: Rule1[String] = rule {
    '\'' ~ capture((noneOf("'") | "''").*) ~ '\'' ~ ws ~> ((s: String) =>
      if (s.indexOf("''") >= 0) s.replace("''", "'") else s)
  }

  final def keyword(k: Keyword): Rule0 = rule {
    atomic(ignoreCase(k.lower)) ~ delimiter
  }

  /**
   * Used for DataTypes. Not reserved and otherwise identical to "keyword"
   * apart from the name so as to appear properly in error messages related
   * to incorrect DataType definition.
   */
  protected final def newDataType(t: Keyword): Rule0 = rule {
    atomic(ignoreCase(t.lower)) ~ delimiter
  }

  final def sql: Rule1[LogicalPlan] = rule {
    ws ~ start ~ (';' ~ ws).* ~ EOI
  }

  protected def start: Rule1[LogicalPlan]

  protected final def unquotedIdentifier: Rule1[String] = rule {
    atomic(capture(Consts.alphaUnderscore ~ Consts.identifier.*)) ~ delimiter
  }

  protected final def identifier: Rule1[String] = rule {
    unquotedIdentifier ~> { (s: String) =>
      val ucase = Utils.toUpperCase(s)
      test(!Consts.reservedKeywords.contains(ucase)) ~
          push(if (caseSensitive) s else ucase)
    } |
    quotedIdentifier
  }

  protected final def quotedIdentifier: Rule1[String] = rule {
    atomic('`' ~ capture((noneOf("`") | "``"). +) ~ '`') ~ ws ~> { (s: String) =>
      if (s.indexOf("``") >= 0) s.replace("``", "`") else s
    } |
    atomic('"' ~ capture((noneOf("\"") | "\"\""). +) ~ '"') ~ ws ~> { (s: String) =>
      if (s.indexOf("\"\"") >= 0) s.replace("\"\"", "\"") else s
    }
  }

  /**
   * A strictIdentifier is more restricted than an identifier in that neither
   * any of the SQL reserved keywords nor non-reserved keywords will be
   * interpreted as a strictIdentifier.
   */
  protected final def strictIdentifier: Rule1[String] = rule {
    unquotedIdentifier ~> { (s: String) =>
      val ucase = Utils.toUpperCase(s)
      test(!Consts.allKeywords.contains(ucase)) ~
          push(if (caseSensitive) s else ucase)
    } |
    quotedIdentifier
  }

  // DataTypes
  // It is not useful to see long list of "expected ARRAY or BIGINT or ..."
  // for parse errors, so not making these separate rules and instead naming
  // the common rule as "datatype" which is otherwise identical to "keyword"
  final def ARRAY: Rule0 = newDataType(Consts.ARRAY)
  final def BIGINT: Rule0 = newDataType(Consts.BIGINT)
  final def BINARY: Rule0 = newDataType(Consts.BINARY)
  final def BLOB: Rule0 = newDataType(Consts.BLOB)
  final def BOOLEAN: Rule0 = newDataType(Consts.BOOLEAN)
  final def BYTE: Rule0 = newDataType(Consts.BYTE)
  final def CHAR: Rule0 = newDataType(Consts.CHAR)
  final def CLOB: Rule0 = newDataType(Consts.CLOB)
  final def DATE: Rule0 = newDataType(Consts.DATE)
  final def DECIMAL: Rule0 = newDataType(Consts.DECIMAL)
  final def DOUBLE: Rule0 = newDataType(Consts.DOUBLE)
  final def FLOAT: Rule0 = newDataType(Consts.FLOAT)
  final def INT: Rule0 = newDataType(Consts.INT)
  final def INTEGER: Rule0 = newDataType(Consts.INTEGER)
  final def LONG: Rule0 = newDataType(Consts.LONG)
  final def MAP: Rule0 = newDataType(Consts.MAP)
  final def NUMERIC: Rule0 = newDataType(Consts.NUMERIC)
  final def REAL: Rule0 = newDataType(Consts.REAL)
  final def SHORT: Rule0 = newDataType(Consts.SHORT)
  final def SMALLINT: Rule0 = newDataType(Consts.SMALLINT)
  final def STRING: Rule0 = newDataType(Consts.STRING)
  final def STRUCT: Rule0 = newDataType(Consts.STRUCT)
  final def TIMESTAMP: Rule0 = newDataType(Consts.TIMESTAMP)
  final def TINYINT: Rule0 = newDataType(Consts.TINYINT)
  final def VARBINARY: Rule0 = newDataType(Consts.VARBINARY)
  final def VARCHAR: Rule0 = newDataType(Consts.VARCHAR)

  protected final def fixedDecimalType: Rule1[DataType] = rule {
    (DECIMAL | NUMERIC) ~ '(' ~ ws ~ digits ~ commaSep ~ digits ~ ')' ~ ws ~>
        ((precision: String, scale: String) =>
          DecimalType(precision.toInt, scale.toInt))
  }

  protected final def primitiveType: Rule1[DataType] = rule {
    STRING ~> (() => StringType) |
    INTEGER ~> (() => IntegerType) |
    INT ~> (() => IntegerType) |
    BIGINT ~> (() => LongType) |
    LONG ~> (() => LongType) |
    DOUBLE ~> (() => DoubleType) |
    fixedDecimalType |
    DECIMAL ~> (() => DecimalType.SYSTEM_DEFAULT) |
    NUMERIC ~> (() => DecimalType.SYSTEM_DEFAULT) |
    DATE ~> (() => DateType) |
    TIMESTAMP ~> (() => TimestampType) |
    FLOAT ~> (() => FloatType) |
    REAL ~> (() => FloatType) |
    BOOLEAN ~> (() => BooleanType) |
    CLOB ~> (() => StringType) |
    BLOB ~> (() => BinaryType) |
    BINARY ~> (() => BinaryType) |
    VARBINARY ~> (() => BinaryType) |
    SMALLINT ~> (() => ShortType) |
    SHORT ~> (() => ShortType) |
    TINYINT ~> (() => ByteType) |
    BYTE ~> (() => ByteType)
  }

  protected final def charType: Rule1[DataType] = rule {
    VARCHAR ~ '(' ~ ws ~ digits ~ ')' ~ ws ~> ((_: String) => StringType) |
    CHAR ~ '(' ~ ws ~ digits ~ ')' ~ ws ~> ((_: String) => StringType)
  }

  final def dataType: Rule1[DataType] = rule {
    charType | primitiveType | arrayType | mapType | structType
  }

  protected final def arrayType: Rule1[DataType] = rule {
    ARRAY ~ '<' ~ ws ~ dataType ~ '>' ~ ws ~>
        ((t: DataType) => ArrayType(t))
  }

  protected final def mapType: Rule1[DataType] = rule {
    MAP ~ '<' ~ ws ~ dataType ~ commaSep ~ dataType ~ '>' ~ ws ~>
        ((t1: DataType, t2: DataType) => MapType(t1, t2))
  }

  protected final def structField: Rule1[StructField] = rule {
    identifier ~ ':' ~ ws ~ dataType ~> ((name: String, t: DataType) =>
      StructField(name, t, nullable = true))
  }

  protected final def structType: Rule1[DataType] = rule {
    STRUCT ~ '<' ~ ws ~ (structField * commaSep) ~ '>' ~ ws ~>
        ((f: Any) => StructType(f.asInstanceOf[Seq[StructField]].toArray))
  }

  protected final def columnCharType: Rule1[DataType] = rule {
    VARCHAR ~ '(' ~ ws ~ digits ~ ')' ~ ws ~> ((d: String) =>
      CharStringType(d.toInt, baseType = "VARCHAR")) |
    CHAR ~ '(' ~ ws ~ digits ~ ')' ~ ws ~> ((d: String) =>
      CharStringType(d.toInt, baseType = "CHAR")) |
    STRING ~> (() => CharStringType(Constant.MAX_VARCHAR_SIZE, baseType = "STRING"))
  }

  final def columnDataType: Rule1[DataType] = rule {
    columnCharType | primitiveType | arrayType | mapType | structType
  }

  final def tableIdentifier: Rule1[TableIdentifier] = rule {
    // case-sensitivity already taken care of properly by "identifier"
    (identifier ~ '.' ~ ws).? ~ identifier ~> ((schema: Any, table: String) =>
      TableIdentifier(table, schema.asInstanceOf[Option[String]]))
  }

  final def functionIdentifier: Rule1[FunctionIdentifier] = rule {
    // case-sensitivity already taken care of properly by "identifier"
    (identifier ~ '.' ~ ws).? ~ identifier ~> ((schema: Any, name: String) =>
      FunctionIdentifier(name, database = schema.asInstanceOf[Option[String]]))
  }
}

final class Keyword private[sql] (s: String) {
  val lower: String = Utils.toLowerCase(s)
  val upper: String = Utils.toUpperCase(s)
}

final class ParseException(msg: String, cause: Option[Throwable] = None)
    extends AnalysisException(msg, None, None, None, cause)

object SnappyParserConsts {
  final val space: CharPredicate = CharPredicate(' ', '\t')
  final val whitespace: CharPredicate = CharPredicate(
    ' ', '\t', '\n', '\r', '\f')
  final val delimiters: CharPredicate = CharPredicate('@', '*',
    '+', '-', '<', '=', '!', '>', '/', '(', ')', ',', ';', '%', '{', '}', ':',
    '[', ']', '.', '&', '|', '^', '~', '#')
  final val lineCommentEnd: String = "\n\r\f" + EOI
  final val lineHintEnd: String = ")\n\r\f" + EOI
  final val hintValueEnd: String = ")*" + EOI
  final val underscore: CharPredicate = CharPredicate('_')
  final val identifier: CharPredicate = CharPredicate.AlphaNum ++ underscore
  final val alphaUnderscore: CharPredicate = CharPredicate.Alpha ++ underscore
  final val plusOrMinus: CharPredicate = CharPredicate('+', '-')
  final val arithmeticOperator = CharPredicate('*', '/', '%', '&', '|', '^')
  final val exponent: CharPredicate = CharPredicate('e', 'E')
  final val numeric: CharPredicate = CharPredicate.Digit ++
      CharPredicate('.')
  final val numericSuffix: CharPredicate = CharPredicate('D', 'd', 'F', 'f', 'L', 'l', 'B', 'b')
  final val plural: CharPredicate = CharPredicate('s', 'S')

  final val reservedKeywords: OpenHashSet[String] = new OpenHashSet[String]

  final val allKeywords: OpenHashSet[String] = new OpenHashSet[String]

  final val optimizableLikePattern: java.util.regex.Pattern =
    java.util.regex.Pattern.compile("(%?[^_%]*[^_%\\\\]%?)|([^_%]*[^_%\\\\]%[^_%]*)")

  /**
   * Registering a Keyword with this method marks it a reserved keyword,
   * i.e. it is interpreted as a keyword wherever it may appear and is never
   * interpreted as an identifier (except if quoted).
   * <p>
   * Use this only for SQL reserved keywords.
   */
  private[sql] def reservedKeyword(s: String): Keyword = {
    val k = new Keyword(s)
    reservedKeywords.add(k.upper)
    allKeywords.add(k.upper)
    k
  }

  /**
   * Registering a Keyword with this method marks it a non-reserved keyword.
   * These can be interpreted as identifiers as per the parsing rules,
   * but never interpreted as a "strictIdentifier". In other words, use
   * "strictIdentifier" in parsing rules where there can be an ambiguity
   * between an identifier and a non-reserved keyword.
   * <p>
   * Use this for all SQL keywords used by grammar that are not reserved.
   */
  private[sql] def nonReservedKeyword(s: String): Keyword = {
    val k = new Keyword(s)
    allKeywords.add(k.upper)
    k
  }

  final val COLUMN_SOURCE = "column"
  final val ROW_SOURCE = "row"
  final val DEFAULT_SOURCE = ROW_SOURCE

  // reserved keywords
  final val ALL: Keyword = reservedKeyword("all")
  final val AND: Keyword = reservedKeyword("and")
  final val AS: Keyword = reservedKeyword("as")
  final val ASC: Keyword = reservedKeyword("asc")
  final val BETWEEN: Keyword = reservedKeyword("between")
  final val BY: Keyword = reservedKeyword("by")
  final val CASE: Keyword = reservedKeyword("case")
  final val CAST: Keyword = reservedKeyword("cast")
  final val CREATE: Keyword = reservedKeyword("create")
  final val CURRENT: Keyword = reservedKeyword("current")
  final val CURRENT_DATE: Keyword = reservedKeyword("current_date")
  final val CURRENT_TIMESTAMP: Keyword = reservedKeyword("current_timestamp")
  final val DELETE: Keyword = reservedKeyword("delete")
  final val DESC: Keyword = reservedKeyword("desc")
  final val DISTINCT: Keyword = reservedKeyword("distinct")
  final val DROP: Keyword = reservedKeyword("drop")
  final val ELSE: Keyword = reservedKeyword("else")
  final val EXCEPT: Keyword = reservedKeyword("except")
  final val EXISTS: Keyword = reservedKeyword("exists")
  final val FALSE: Keyword = reservedKeyword("false")
  final val FROM: Keyword = reservedKeyword("from")
  final val GROUP: Keyword = reservedKeyword("group")
  final val HAVING: Keyword = reservedKeyword("having")
  final val IN: Keyword = reservedKeyword("in")
  final val INNER: Keyword = reservedKeyword("inner")
  final val INSERT: Keyword = reservedKeyword("insert")
  final val INTERSECT: Keyword = reservedKeyword("intersect")
  final val INTO: Keyword = reservedKeyword("into")
  final val IS: Keyword = reservedKeyword("is")
  final val JOIN: Keyword = reservedKeyword("join")
  final val LEFT: Keyword = reservedKeyword("left")
  final val LIKE: Keyword = reservedKeyword("like")
  final val NOT: Keyword = reservedKeyword("not")
  final val NULL: Keyword = reservedKeyword("null")
  final val ON: Keyword = reservedKeyword("on")
  final val OR: Keyword = reservedKeyword("or")
  final val ORDER: Keyword = reservedKeyword("order")
  final val OUTER: Keyword = reservedKeyword("outer")
  final val RIGHT: Keyword = reservedKeyword("right")
  final val SCHEMA: Keyword = reservedKeyword("schema")
  final val SELECT: Keyword = reservedKeyword("select")
  final val SET: Keyword = reservedKeyword("set")
  final val TABLE: Keyword = reservedKeyword("table")
  final val THEN: Keyword = reservedKeyword("then")
  final val TO: Keyword = reservedKeyword("to")
  final val TRUE: Keyword = reservedKeyword("true")
  final val UNION: Keyword = reservedKeyword("union")
  final val UNIQUE: Keyword = reservedKeyword("unique")
  final val UPDATE: Keyword = reservedKeyword("update")
  final val WHEN: Keyword = reservedKeyword("when")
  final val WHERE: Keyword = reservedKeyword("where")
  final val WITH: Keyword = reservedKeyword("with")
  final val FUNCTIONS: Keyword = reservedKeyword("functions")
  final val FUNCTION: Keyword = reservedKeyword("function")

  // marked as internal keywords to prevent use in SQL
  final val HIVE_METASTORE: Keyword = reservedKeyword(SystemProperties.SNAPPY_HIVE_METASTORE)

  final val SAMPLER_WEIGHTAGE: Keyword = nonReservedKeyword(
    Utils.WEIGHTAGE_COLUMN_NAME)

  // non-reserved keywords
  final val ADD: Keyword = nonReservedKeyword("add")
  final val ALTER: Keyword = nonReservedKeyword("alter")
  final val ANTI: Keyword = nonReservedKeyword("anti")
  final val CACHE: Keyword = nonReservedKeyword("cache")
  final val CLEAR: Keyword = nonReservedKeyword("clear")
  final val CLUSTER: Keyword = nonReservedKeyword("cluster")
  final val COLUMN: Keyword = nonReservedKeyword("column")
  final val COMMENT: Keyword = nonReservedKeyword("comment")
  final val DESCRIBE: Keyword = nonReservedKeyword("describe")
  final val DISTRIBUTE: Keyword = nonReservedKeyword("distribute")
  final val END: Keyword = nonReservedKeyword("end")
  final val EXTENDED: Keyword = nonReservedKeyword("extended")
  final val EXTERNAL: Keyword = nonReservedKeyword("external")
  final val FETCH: Keyword = nonReservedKeyword("fetch")
  final val FIRST: Keyword = nonReservedKeyword("first")
  final val FN: Keyword = nonReservedKeyword("fn")
  final val FULL: Keyword = nonReservedKeyword("full")
  final val GLOBAL: Keyword = nonReservedKeyword("global")
  final val GRANT: Keyword = nonReservedKeyword("grant")
  final val HASH: Keyword = nonReservedKeyword("hash")
  final val IF: Keyword = nonReservedKeyword("if")
  final val INDEX: Keyword = nonReservedKeyword("index")
  final val INIT: Keyword = nonReservedKeyword("init")
  final val INTERVAL: Keyword = nonReservedKeyword("interval")
  final val LAST: Keyword = nonReservedKeyword("last")
  final val LAZY: Keyword = nonReservedKeyword("lazy")
  final val LIMIT: Keyword = nonReservedKeyword("limit")
  final val NATURAL: Keyword = nonReservedKeyword("natural")
  final val NULLS: Keyword = nonReservedKeyword("nulls")
  final val ONLY: Keyword = nonReservedKeyword("only")
  final val OPTIONS: Keyword = nonReservedKeyword("options")
  final val OVERWRITE: Keyword = nonReservedKeyword("overwrite")
  final val PARTITION: Keyword = nonReservedKeyword("partition")
  final val PUT: Keyword = nonReservedKeyword("put")
  final val REFRESH: Keyword = nonReservedKeyword("refresh")
  final val REGEXP: Keyword = nonReservedKeyword("regexp")
  final val REPLACE: Keyword = nonReservedKeyword("replace")
  final val REVOKE: Keyword = nonReservedKeyword("revoke")
  final val RLIKE: Keyword = nonReservedKeyword("rlike")
  final val SEMI: Keyword = nonReservedKeyword("semi")
  final val SHOW: Keyword = nonReservedKeyword("show")
  final val SORT: Keyword = nonReservedKeyword("sort")
  final val START: Keyword = nonReservedKeyword("start")
  final val STOP: Keyword = nonReservedKeyword("stop")
  final val STREAM: Keyword = nonReservedKeyword("stream")
  final val STREAMING: Keyword = nonReservedKeyword("streaming")
  final val TABLES: Keyword = nonReservedKeyword("tables")
  final val TEMPORARY: Keyword = nonReservedKeyword("temporary")
  final val TRUNCATE: Keyword = nonReservedKeyword("truncate")
  final val UNCACHE: Keyword = nonReservedKeyword("uncache")
  final val USING: Keyword = nonReservedKeyword("using")
  final val VALUES: Keyword = nonReservedKeyword("values")
  final val VIEW: Keyword = nonReservedKeyword("view")

  // Window analytical functions are non-reserved
  final val DURATION: Keyword = nonReservedKeyword("duration")
  final val FOLLOWING: Keyword = nonReservedKeyword("following")
  final val OVER: Keyword = nonReservedKeyword("over")
  final val PRECEDING: Keyword = nonReservedKeyword("preceding")
  final val RANGE: Keyword = nonReservedKeyword("range")
  final val ROW: Keyword = nonReservedKeyword("row")
  final val ROWS: Keyword = nonReservedKeyword("rows")
  final val SLIDE: Keyword = nonReservedKeyword("slide")
  final val UNBOUNDED: Keyword = nonReservedKeyword("unbounded")
  final val WINDOW: Keyword = nonReservedKeyword("window")

  // interval units are not reserved
  final val DAY: Keyword = nonReservedKeyword("day")
  final val HOUR: Keyword = nonReservedKeyword("hour")
  final val MICROSECOND: Keyword = nonReservedKeyword("microsecond")
  final val MILLISECOND: Keyword = nonReservedKeyword("millisecond")
  final val MINUTE: Keyword = nonReservedKeyword("minute")
  final val MONTH: Keyword = nonReservedKeyword("month")
  final val SECOND: Keyword = nonReservedKeyword("seconds")
  final val WEEK: Keyword = nonReservedKeyword("week")
  final val YEAR: Keyword = nonReservedKeyword("year")

  // cube, rollup, grouping sets etc are not reserved
  final val CUBE: Keyword = nonReservedKeyword("cube")
  final val ROLLUP: Keyword = nonReservedKeyword("rollup")
  final val GROUPING: Keyword = nonReservedKeyword("grouping")
  final val SETS: Keyword = nonReservedKeyword("sets")
  final val LATERAL: Keyword = nonReservedKeyword("lateral")

  // datatypes are not reserved
  final val ARRAY: Keyword = nonReservedKeyword("array")
  final val BIGINT: Keyword = nonReservedKeyword("bigint")
  final val BINARY: Keyword = nonReservedKeyword("binary")
  final val BLOB: Keyword = nonReservedKeyword("blob")
  final val BOOLEAN: Keyword = nonReservedKeyword("boolean")
  final val BYTE: Keyword = nonReservedKeyword("byte")
  final val CHAR: Keyword = nonReservedKeyword("char")
  final val CLOB: Keyword = nonReservedKeyword("clob")
  final val DATE: Keyword = nonReservedKeyword("date")
  final val DECIMAL: Keyword = nonReservedKeyword("decimal")
  final val DOUBLE: Keyword = nonReservedKeyword("double")
  final val FLOAT: Keyword = nonReservedKeyword("float")
  final val INT: Keyword = nonReservedKeyword("int")
  final val INTEGER: Keyword = nonReservedKeyword("integer")
  final val LONG: Keyword = nonReservedKeyword("long")
  final val MAP: Keyword = nonReservedKeyword("map")
  final val NUMERIC: Keyword = nonReservedKeyword("numeric")
  final val REAL: Keyword = nonReservedKeyword("real")
  final val SHORT: Keyword = nonReservedKeyword("short")
  final val SMALLINT: Keyword = nonReservedKeyword("smallint")
  final val STRING: Keyword = nonReservedKeyword("string")
  final val STRUCT: Keyword = nonReservedKeyword("struct")
  final val TIMESTAMP: Keyword = nonReservedKeyword("timestamp")
  final val TINYINT: Keyword = nonReservedKeyword("tinyint")
  final val VARBINARY: Keyword = nonReservedKeyword("varbinary")
  final val VARCHAR: Keyword = nonReservedKeyword("varchar")

  // for AQP
  final val ERROR: Keyword = nonReservedKeyword("error")
  final val ESTIMATE: Keyword = nonReservedKeyword("estimate")
  final val CONFIDENCE: Keyword = nonReservedKeyword("confidence")
  final val BEHAVIOR: Keyword = nonReservedKeyword("behavior")
  final val SAMPLE: Keyword = nonReservedKeyword("sample")
  final val TOPK: Keyword = nonReservedKeyword("topk")

  // keywords that are neither reserved nor non-reserved and can be freely
  // used as named strictIdentifier
  final val RETURNS: Keyword = new Keyword("returns")
}
