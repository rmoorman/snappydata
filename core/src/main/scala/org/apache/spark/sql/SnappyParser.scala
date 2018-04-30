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

import java.util.function.BiConsumer

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

import com.google.common.primitives.Ints
import io.snappydata.{Constant, Property, QueryHint}
import org.parboiled2._
import shapeless.{::, HNil}

import org.apache.spark.sql.SnappyParserConsts.plusOrMinus
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete, Count}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, _}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.internal.LikeEscapeSimplification
import org.apache.spark.sql.sources.{Delete, Insert, PutIntoTable, Update}
import org.apache.spark.sql.streaming.WindowLogicalPlan
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SnappyParserConsts => Consts}
import org.apache.spark.streaming.Duration
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

class SnappyParser(session: SnappySession)
    extends SnappyDDLParser(session) with ParamLiteralHolder {

  private[this] final var _input: ParserInput = _

  protected final var _questionMarkCounter: Int = _
  protected final var _isPreparePhase: Boolean = _
  protected final var _parameterValueSet: Option[_] = None

  override final def input: ParserInput = _input

  final def questionMarkCounter: Int = _questionMarkCounter

  private[sql] final def input_=(in: ParserInput): Unit = {
    reset()
    _input = in
    clearConstants()
    _questionMarkCounter = 0
    tokenize = false
  }

  private[sql] def setPreparedQuery(preparePhase: Boolean, paramSet: Option[_]): Unit = {
    _isPreparePhase = preparePhase
    _parameterValueSet = paramSet
  }

  protected final type WhenElseType = (Seq[(Expression, Expression)],
      Option[Expression])
  protected final type JoinRuleType = (Option[JoinType], LogicalPlan,
      Option[Expression])

  private def toDecimalLiteral(s: String, checkExactNumeric: Boolean): Expression = {
    val decimal = BigDecimal(s)
    if (checkExactNumeric) {
      try {
        return newTokenizedLiteral(decimal.toIntExact, IntegerType)
      } catch {
        case _: ArithmeticException =>
          try {
            return newTokenizedLiteral(decimal.toLongExact, LongType)
          } catch {
            case _: ArithmeticException =>
          }
      }
    }
    val precision = decimal.precision
    val scale = decimal.scale
    val sysDefaultType = DecimalType.SYSTEM_DEFAULT
    if (precision == sysDefaultType.precision &&
        scale == sysDefaultType.scale) {
      newTokenizedLiteral(Decimal(decimal), sysDefaultType)
    } else {
      newTokenizedLiteral(Decimal(decimal), DecimalType(Math.max(precision, scale), scale))
    }
  }

  private def toNumericLiteral(s: String): Expression = {
    // quick pass through the string to check for floats
    var noDecimalPoint = true
    var index = 0
    val len = s.length
    // use double if ending with D/d, float for F/f and long for L/l

    s.charAt(len - 1) match {
      case 'D' | 'd' =>
        if (s.length > 2 && (s.charAt(len - 2) match {
          case 'B' | 'b' => true
          case _ => false
        })) {
          return toDecimalLiteral(s.substring(0, len - 2), checkExactNumeric = false)
        } else {
          return newTokenizedLiteral(
            java.lang.Double.parseDouble(s.substring(0, len - 1)), DoubleType)
        }
      case 'F' | 'f' =>
        return newTokenizedLiteral(
          java.lang.Float.parseFloat(s.substring(0, len - 1)), FloatType)
      case 'L' | 'l' =>
        return newTokenizedLiteral(
          java.lang.Long.parseLong(s.substring(0, len - 1)), LongType)
      case _ =>
    }
    while (index < len) {
      val c = s.charAt(index)
      if (noDecimalPoint && c == '.') {
        noDecimalPoint = false
      } else if (c == 'e' || c == 'E') {
        // follow the behavior in MS SQL Server
        // https://msdn.microsoft.com/en-us/library/ms179899.aspx
        return newTokenizedLiteral(java.lang.Double.parseDouble(s), DoubleType)
      }
      index += 1
    }
    if (noDecimalPoint) {
      // case of integral value
      // most cases should be handled by Long, so try that first
      try {
        val longValue = java.lang.Long.parseLong(s)
        if (longValue >= Int.MinValue && longValue <= Int.MaxValue) {
          newTokenizedLiteral(longValue.toInt, IntegerType)
        } else {
          newTokenizedLiteral(longValue, LongType)
        }
      } catch {
        case _: NumberFormatException =>
          toDecimalLiteral(s, checkExactNumeric = true)
      }
    } else {
      toDecimalLiteral(s, checkExactNumeric = false)
    }

  }

  private final def updatePerTableQueryHint(tableIdent: TableIdentifier,
      optAlias: Option[String]) = {
    val indexHint = queryHints.remove(QueryHint.Index.toString)
    if (indexHint ne null) {
      val table = optAlias match {
        case Some(alias) => alias
        case _ => tableIdent.unquotedString
      }
      queryHints.put(QueryHint.Index.toString + table, indexHint)
    }
  }

  private final def assertNoQueryHint(hint: QueryHint.Value, msg: => String) = {
    if (!queryHints.isEmpty) {
      val hintStr = hint.toString
      queryHints.forEach(new BiConsumer[String, String] {
        override def accept(key: String, value: String): Unit = {
          if (key.startsWith(hintStr)) throw new ParseException(msg)
        }
      })
    }
  }

  protected final def booleanLiteral: Rule1[Expression] = rule {
    TRUE ~> (() => newTokenizedLiteral(true, BooleanType)) |
    FALSE ~> (() => newTokenizedLiteral(false, BooleanType))
  }

  protected final def numericLiteral: Rule1[Expression] = rule {
    capture(plusOrMinus.? ~ Consts.numeric. + ~ (Consts.exponent ~
        plusOrMinus.? ~ CharPredicate.Digit. +).? ~ Consts.numericSuffix.? ~
        Consts.numericSuffix.?) ~ delimiter ~> ((s: String) => toNumericLiteral(s))
  }

  protected final def literal: Rule1[Expression] = rule {
    stringLiteral ~> ((s: String) => newTokenizedLiteral(UTF8String.fromString(s), StringType)) |
    numericLiteral |
    booleanLiteral |
    NULL ~> (() => newTokenizedLiteral(null, NullType))
  }

  protected final def paramLiteralQuestionMark: Rule1[Expression] = rule {
    questionMark ~> (() => {
      _questionMarkCounter += 1
      if (_isPreparePhase) {
        ParamLiteral(Row(_questionMarkCounter), NullType, 0, execId = -1, tokenized = true)
      } else {
        assert(_parameterValueSet.isDefined,
          "For Prepared Statement, Parameter constants are not provided")
        val (scalaTypeVal, dataType) = session.getParameterValue(
          _questionMarkCounter, _parameterValueSet.get)
        val catalystTypeVal = CatalystTypeConverters.convertToCatalyst(scalaTypeVal)
        newTokenizedLiteral(catalystTypeVal, dataType)
      }
    })
  }

  private[sql] final def addTokenizedLiteral(v: Any, dataType: DataType): TokenizedLiteral = {
    if (session.planCaching) addParamLiteralToContext(v, dataType)
    else new TokenLiteral(v, dataType)
  }

  protected final def newTokenizedLiteral(v: Any, dataType: DataType): Expression = {
    if (tokenize) addTokenizedLiteral(v, dataType) else Literal(v, dataType)
  }

  protected final def newLiteral(v: Any, dataType: DataType): Expression = {
    if (tokenize) new TokenLiteral(v, dataType).markFoldable(true) else Literal(v, dataType)
  }

  protected final def month: Rule1[Int] = rule {
    integral ~ MONTH ~> ((num: String) => num.toInt)
  }

  protected final def year: Rule1[Int] = rule {
    integral ~ YEAR ~> ((num: String) => num.toInt)
  }

  protected final def microsecond: Rule1[Long] = rule {
    integral ~ (MICROS | MICROSECOND) ~> ((num: String) => num.toLong)
  }

  protected final def millisecond: Rule1[Long] = rule {
    integral ~ (MILLIS | MILLISECOND) ~> ((num: String) => num.toLong)
  }

  protected final def second: Rule1[Long] = rule {
    integral ~ (SECS | SECOND) ~> ((num: String) => num.toLong)
  }

  protected final def minute: Rule1[Long] = rule {
    integral ~ (MINS | MINUTE) ~> ((num: String) => num.toLong)
  }

  protected final def hour: Rule1[Long] = rule {
    integral ~ HOUR ~> ((num: String) => num.toLong)
  }

  protected final def day: Rule1[Long] = rule {
    integral ~ DAY ~> ((num: String) => num.toLong)
  }

  protected final def week: Rule1[Long] = rule {
    integral ~ WEEK ~> ((num: String) => num.toLong)
  }

  protected def intervalLiteral: Rule1[Expression] = rule {
    INTERVAL ~ (
        stringLiteral ~ (
            YEAR ~ TO ~ MONTH ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromYearMonthString(s), CalendarIntervalType)) |
            DAY ~ TO ~ (SECS | SECOND) ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromDayTimeString(s), CalendarIntervalType)) |
            YEAR ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("year", s), CalendarIntervalType)) |
            MONTH ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("month", s), CalendarIntervalType)) |
            DAY ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("day", s), CalendarIntervalType)) |
            HOUR ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("hour", s), CalendarIntervalType)) |
            (MINS | MINUTE) ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("minute", s), CalendarIntervalType)) |
            (SECS | SECOND) ~> ((s: String) => newTokenizedLiteral(
              CalendarInterval.fromSingleUnitString("second", s), CalendarIntervalType))
        ) |
        year.? ~ month.? ~ week.? ~ day.? ~ hour.? ~ minute.? ~
            second.? ~ millisecond.? ~ microsecond.? ~> { (y: Any, m: Any,
            w: Any, d: Any, h: Any, m2: Any, s: Any, m3: Any, m4: Any) =>
          val year = y.asInstanceOf[Option[Int]]
          val month = m.asInstanceOf[Option[Int]]
          val week = w.asInstanceOf[Option[Int]]
          val day = d.asInstanceOf[Option[Long]]
          val hour = h.asInstanceOf[Option[Long]]
          val minute = m2.asInstanceOf[Option[Long]]
          val second = s.asInstanceOf[Option[Long]]
          val millis = m3.asInstanceOf[Option[Long]]
          val micros = m4.asInstanceOf[Option[Long]]
          if (!Seq(year, month, week, day, hour, minute, second, millis,
            micros).exists(_.isDefined)) {
            throw new ParseException(
              "at least one time unit should be given for interval literal")
          }
          val months = year.map(_ * 12).getOrElse(0) + month.getOrElse(0)
          val microseconds =
            week.map(_ * CalendarInterval.MICROS_PER_WEEK).getOrElse(0L) +
            day.map(_ * CalendarInterval.MICROS_PER_DAY).getOrElse(0L) +
            hour.map(_ * CalendarInterval.MICROS_PER_HOUR).getOrElse(0L) +
            minute.map(_ * CalendarInterval.MICROS_PER_MINUTE).getOrElse(0L) +
            second.map(_ * CalendarInterval.MICROS_PER_SECOND).getOrElse(0L) +
            millis.map(_ * CalendarInterval.MICROS_PER_MILLI).getOrElse(0L) +
            micros.getOrElse(0L)
          newTokenizedLiteral(new CalendarInterval(months, microseconds), CalendarIntervalType)
        }
    )
  }

  protected final def unsignedFloat: Rule1[String] = rule {
    capture(
      CharPredicate.Digit.* ~ '.' ~ CharPredicate.Digit. + ~
          scientificNotation.? |
      CharPredicate.Digit. + ~ scientificNotation
    ) ~ ws
  }

  final def namedExpression: Rule1[Expression] = rule {
    expression ~ (
        AS ~ identifier ~> ((e: Expression, a: String) => Alias(e, a)()) |
        strictIdentifier ~> ((e: Expression, a: String) => Alias(e, a)()) |
        MATCH.asInstanceOf[Rule[Expression::HNil, Expression::HNil]]
    )
  }

  final def parsedDataType: Rule1[DataType] = rule {
    ws ~ dataType ~ EOI
  }

  final def parsedExpression: Rule1[Expression] = rule {
    ws ~ namedExpression ~ EOI
  }

  final def parsedTableIdentifier: Rule1[TableIdentifier] = rule {
    ws ~ tableIdentifier ~ EOI
  }

  protected final def expression: Rule1[Expression] = rule {
    andExpression ~ (OR ~ andExpression ~>
        ((e1: Expression, e2: Expression) => Or(e1, e2))).*
  }

  protected final def expressionNoTokens: Rule1[Expression] = rule {
    push(tokenize) ~ TOKENIZE_END ~ expression ~> { (tokenized: Boolean, e: Expression) =>
      tokenize = tokenized
      e
    }
  }

  protected final def andExpression: Rule1[Expression] = rule {
    notExpression ~ (AND ~ notExpression ~>
        ((e1: Expression, e2: Expression) => And(e1, e2))).*
  }

  protected final def notExpression: Rule1[Expression] = rule {
    (NOT ~ push(true)).? ~ comparisonExpression ~> ((not: Any, e: Expression) =>
      if (not.asInstanceOf[Option[Boolean]].isEmpty) e else Not(e))
  }

  protected final def comparisonExpression: Rule1[Expression] = rule {
    termExpression ~ (
        '=' ~ ws ~ termExpression ~> EqualTo |
        '>' ~ (
          '=' ~ ws ~ termExpression ~> GreaterThanOrEqual |
          '>' ~ (
            '>' ~ ws ~ termExpression ~> ShiftRightUnsigned |
            ws ~ termExpression ~> ShiftRight
          ) |
          ws ~ termExpression ~> GreaterThan
        ) |
        '<' ~ (
          '=' ~ (
            '>' ~ ws ~ termExpression ~> EqualNullSafe |
            ws ~ termExpression ~> LessThanOrEqual
          ) |
          '>' ~ ws ~ termExpression ~>
              ((e1: Expression, e2: Expression) => Not(EqualTo(e1, e2))) |
          '<' ~ ws ~ termExpression ~> ShiftLeft |
          ws ~ termExpression ~> LessThan
        ) |
        '!' ~ '=' ~ ws ~ termExpression ~>
            ((e1: Expression, e2: Expression) => Not(EqualTo(e1, e2))) |
        invertibleExpression |
        IS ~ (NOT ~ push(true)).? ~ NULL ~>
            ((e: Expression, not: Any) =>
              if (not.asInstanceOf[Option[Boolean]].isEmpty) IsNull(e)
              else IsNotNull(e)) |
        NOT ~ invertibleExpression ~> Not |
        MATCH.asInstanceOf[Rule[Expression::HNil, Expression::HNil]]
    )
  }

  protected final def likeExpression(left: Expression, right: TokenizedLiteral): Expression = {
    val pattern = right.valueString
    removeIfParamLiteralFromContext(right)
    if (Consts.optimizableLikePattern.matcher(pattern).matches()) {
      val size = pattern.length
      val expression = if (pattern.charAt(0) == '%') {
        if (pattern.charAt(size - 1) == '%') {
          Contains(left, addTokenizedLiteral(
            UTF8String.fromString(pattern.substring(1, size - 1)), StringType))
        } else {
          EndsWith(left, addTokenizedLiteral(
            UTF8String.fromString(pattern.substring(1)), StringType))
        }
      } else if (pattern.charAt(size - 1) == '%') {
        StartsWith(left, addTokenizedLiteral(
          UTF8String.fromString(pattern.substring(0, size - 1)), StringType))
      } else {
        // check for startsWith and endsWith
        val wildcardIndex = pattern.indexOf('%')
        if (wildcardIndex != -1) {
          val prefix = pattern.substring(0, wildcardIndex)
          val postfix = pattern.substring(wildcardIndex + 1)
          val prefixLiteral = addTokenizedLiteral(UTF8String.fromString(prefix), StringType)
          val suffixLiteral = addTokenizedLiteral(UTF8String.fromString(postfix), StringType)
          And(GreaterThanOrEqual(Length(left),
            addTokenizedLiteral(prefix.length + postfix.length, IntegerType)),
            And(StartsWith(left, prefixLiteral), EndsWith(left, suffixLiteral)))
        } else {
          // no wildcards
          EqualTo(left, addTokenizedLiteral(UTF8String.fromString(pattern), StringType))
        }
      }
      expression
    } else {
      LikeEscapeSimplification.simplifyLike(this,
        Like(left, newLiteral(right.value, right.dataType)), left, pattern)
    }
  }

  /**
    * Expressions which can be preceeded by a NOT. This assumes one expression
    * already pushed on stack which it will pop and then push back the result
    * Expression (hence the slightly odd looking type)
    */
  protected final def invertibleExpression: Rule[Expression :: HNil,
      Expression :: HNil] = rule {
    LIKE ~ termExpression ~>
        ((e1: Expression, e2: Expression) => e2 match {
          case l: TokenizedLiteral if !l.value.isInstanceOf[Row] => likeExpression(e1, l)
          case _ => Like(e1, e2)
        }) |
    IN ~ '(' ~ ws ~ (
        (termExpression * commaSep) ~ ')' ~ ws ~> ((e: Expression, es: Any) =>
          In(e, es.asInstanceOf[Seq[Expression]])) |
        query ~ ')' ~ ws ~> ((e1: Expression, plan: LogicalPlan) =>
          In(e1, Seq(ListQuery(plan))))
        ) |
    BETWEEN ~ termExpression ~ AND ~ termExpression ~>
        ((e: Expression, el: Expression, eu: Expression) =>
          And(GreaterThanOrEqual(e, el), LessThanOrEqual(e, eu))) |
    (RLIKE | REGEXP) ~ termExpression ~>
        ((e1: Expression, e2: Expression) => e2 match {
          case l: TokenizedLiteral if !l.value.isInstanceOf[Row] =>
            removeIfParamLiteralFromContext(l)
            RLike(e1, newLiteral(l.value, l.dataType))
          case _ => RLike(e1, e2)
        })
  }

  protected final def termExpression: Rule1[Expression] = rule {
    productExpression ~ (capture(plusOrMinus) ~ ws ~ productExpression ~>
        ((e1: Expression, op: String, e2: Expression) =>
          if (op.charAt(0) == '+') Add(e1, e2) else Subtract(e1, e2))).*
  }

  protected final def productExpression: Rule1[Expression] = rule {
    baseExpression ~ (
        "||" ~ ws ~ baseExpression ~> ((e1: Expression, e2: Expression) =>
          e1 match {
            case Concat(children) => Concat(children :+ e2)
            case _ => Concat(Seq(e1, e2))
          }) |
        capture(Consts.arithmeticOperator) ~ ws ~ baseExpression ~>
            ((e1: Expression, op: String, e2: Expression) =>
          op.charAt(0) match {
            case '*' => Multiply(e1, e2)
            case '/' => Divide(e1, e2)
            case '%' => Remainder(e1, e2)
            case '&' => BitwiseAnd(e1, e2)
            case '|' => BitwiseOr(e1, e2)
            case '^' => BitwiseXor(e1, e2)
            case c => throw new IllegalStateException(
              s"unexpected operation '$c'")
          }) |
        '[' ~ ws ~ baseExpression ~ ']' ~ ws ~> ((base: Expression,
            extraction: Expression) => {
          // extraction should be a literal if type is string (integer can be ParamLiteral)
          val ord = extraction match {
            case l: TokenizedLiteral if l.dataType == StringType =>
              removeIfParamLiteralFromContext(l)
              newLiteral(l.value, l.dataType)
            case o => o
          }
          UnresolvedExtractValue(base, ord)
        }) |
        '.' ~ ws ~ identifier ~> ((base: Expression, fieldName: String) =>
          UnresolvedExtractValue(base, newLiteral(UTF8String.fromString(fieldName), StringType)))
    ).*
  }

  protected final def streamWindowOptions: Rule1[(Duration,
      Option[Duration])] = rule {
    WINDOW ~ '(' ~ ws ~ DURATION ~ durationUnit ~ (commaSep ~
        SLIDE ~ durationUnit).? ~ ')' ~ ws ~> ((d: Duration, s: Any) =>
      (d, s.asInstanceOf[Option[Duration]]))
  }

  protected final def extractGroupingSet(
      child: LogicalPlan,
      aggregations: Seq[NamedExpression],
      groupByExprs: Seq[Expression],
      groupingSets: Seq[Seq[Expression]]): GroupingSets = {
    val keyMap = groupByExprs.zipWithIndex.toMap
    val numExpressions = keyMap.size
    val mask = (1 << numExpressions) - 1
    val bitmasks: Seq[Int] = groupingSets.map(set => set.foldLeft(mask)((bitmap, col) => {
      require(keyMap.contains(col), s"$col doesn't show up in the GROUP BY list")
      bitmap & ~(1 << (numExpressions - 1 - keyMap(col)))
    }))
    GroupingSets(bitmasks, groupByExprs, child, aggregations)
  }

  protected final def groupingSetExpr: Rule1[Seq[Expression]] = rule {
    '(' ~ ws ~ (expression * commaSep) ~ ')' ~ ws ~>
        ((e: Any) => e.asInstanceOf[Seq[Expression]]) |
    (expression + commaSep)
  }

  protected final def cubeRollUpGroupingSet: Rule1[
      (Seq[Seq[Expression]], String)] = rule {
    WITH ~ (
        CUBE ~> (() => (Seq(Seq[Expression]()), "CUBE")) |
        ROLLUP ~> (() => (Seq(Seq[Expression]()), "ROLLUP"))
    ) |
    GROUPING ~ SETS ~ ('(' ~ ws ~ (groupingSetExpr + commaSep) ~ ')' ~ ws)  ~>
        ((gs: Seq[Seq[Expression]]) => (gs, "GROUPINGSETS"))
  }

  protected final def groupBy: Rule1[(Seq[Expression],
      Seq[Seq[Expression]], String)] = rule {
    GROUP ~ BY ~ (expression + commaSep) ~ cubeRollUpGroupingSet.? ~>
        ((g: Any, crgs: Any) => {
          // change top-level tokenized literals to literals for GROUP BY 1 kind of queries
          val groupingExprs = g.asInstanceOf[Seq[Expression]].map {
            case p: ParamLiteral => removeParamLiteralFromContext(p); p.asLiteral
            case l: TokenLiteral => l
            case e => e
          }
          val cubeRollupGrSetExprs = crgs.asInstanceOf[Option[(Seq[
              Seq[Expression]], String)]] match {
            case None => (Seq(Nil), "")
            case Some(e) => e
          }
          (groupingExprs, cubeRollupGrSetExprs._1, cubeRollupGrSetExprs._2)
        })
  }

  protected final def relationFactor: Rule1[LogicalPlan] = rule {
    tableIdentifier ~ streamWindowOptions.? ~
    (AS ~ identifier | strictIdentifier).? ~>
        ((tableIdent: TableIdentifier,
            window: Any, alias: Any) => window.asInstanceOf[Option[
            (Duration, Option[Duration])]] match {
          case None =>
            val optAlias = alias.asInstanceOf[Option[String]]
            updatePerTableQueryHint(tableIdent, optAlias)
            UnresolvedRelation(tableIdent, optAlias)
          case Some(win) =>
            val optAlias = alias.asInstanceOf[Option[String]]
            updatePerTableQueryHint(tableIdent, optAlias)
            WindowLogicalPlan(win._1, win._2,
              UnresolvedRelation(tableIdent, optAlias))
        }) |
    '(' ~ ws ~ start ~ ')' ~ ws ~ streamWindowOptions.? ~
        (AS ~ identifier | strictIdentifier).? ~> { (child: LogicalPlan, w: Any, alias: Any) =>
      val aliasPlan = alias.asInstanceOf[Option[String]] match {
        case None => child
        case Some(name) => SubqueryAlias(name, child, None)
      }
      w.asInstanceOf[Option[(Duration, Option[Duration])]] match {
        case None =>
          assertNoQueryHint(QueryHint.Index,
            s"${QueryHint.Index} cannot be applied to derived table $alias")
          aliasPlan
        case Some(win) =>
          assertNoQueryHint(QueryHint.Index,
            s"${QueryHint.Index} cannot be applied to derived table $alias")
          WindowLogicalPlan(win._1, win._2, aliasPlan)
      }
    }
  }

  protected final def inlineTable: Rule1[LogicalPlan] = rule {
    VALUES ~ push(tokenize) ~ TOKENIZE_BEGIN ~ (expression + commaSep) ~ AS.? ~ identifier.? ~
        ('(' ~ ws ~ (identifier + commaSep) ~ ')' ~ ws).? ~>
        ((tokenized: Boolean, valuesExpr: Seq[Expression], alias: Any, identifiers: Any) => {
          tokenize = tokenized
          val rows = valuesExpr.map {
            // e.g. values (1), (2), (3)
            case struct: CreateNamedStruct => struct.valExprs
            // e.g. values 1, 2, 3
            case child => Seq(child)
          }
          val aliases = identifiers match {
            case None => Seq.tabulate(rows.head.size)(i => s"col${i + 1}")
            case Some(ids) => ids.asInstanceOf[Seq[String]]
          }
          alias match {
            case None => UnresolvedInlineTable(aliases, rows)
            case Some(a) => SubqueryAlias(a.asInstanceOf[String],
              UnresolvedInlineTable(aliases, rows), None)
          }
        })
  }

  protected final def join: Rule1[JoinRuleType] = rule {
    joinType.? ~ JOIN ~ relationWithExternal ~ (
        ON ~ expression ~> ((t: Any, r: LogicalPlan, j: Expression) =>
          (t.asInstanceOf[Option[JoinType]], r, Some(j))) |
        USING ~ '(' ~ ws ~ (identifier + commaSep) ~ ')' ~ ws ~>
            ((t: Any, r: LogicalPlan, ids: Any) =>
              (Some(UsingJoin(t.asInstanceOf[Option[JoinType]]
                  .getOrElse(Inner), ids.asInstanceOf[Seq[String]])), r, None)) |
        MATCH ~> ((t: Option[JoinType], r: LogicalPlan) => (t, r, None))
    ) |
    NATURAL ~ joinType.? ~ JOIN ~ relationWithExternal ~> ((t: Any,
        r: LogicalPlan) => (Some(NaturalJoin(t.asInstanceOf[Option[JoinType]]
        .getOrElse(Inner))), r, None))
  }

  protected final def joinType: Rule1[JoinType] = rule {
    INNER ~> (() => Inner) |
    LEFT ~ (
        SEMI ~> (() => LeftSemi) |
        ANTI ~> (() => LeftAnti) |
        OUTER.? ~> (() => LeftOuter)
    ) |
    RIGHT ~ OUTER.? ~> (() => RightOuter) |
    FULL ~ OUTER.? ~> (() => FullOuter) |
    ANTI ~> (() => LeftAnti)
  }

  protected final def ordering: Rule1[Seq[SortOrder]] = rule {
    ((expression ~ sortDirection.? ~ (NULLS ~ (FIRST ~ push(true) | LAST ~ push(false))).? ~>
        ((e: Expression, d: Any, n: Any) => (e, d, n))) + commaSep) ~> ((exprs: Any) =>
      exprs.asInstanceOf[Seq[(Expression, Option[SortDirection], Option[Boolean])]].map {
        case (c, d, n) =>
          // change top-level tokenized literals to literals for ORDER BY 1 kind of queries
          val child = c match {
            case p: ParamLiteral => removeParamLiteralFromContext(p); p.asLiteral
            case l: TokenLiteral => l
            case _ => c
          }
          val direction = d match {
            case Some(v) => v
            case None => Ascending
          }
          val nulls = n match {
            case Some(false) => NullsLast
            case Some(true) => NullsFirst
            case None => direction.defaultNullOrdering
          }
          SortOrder(child, direction, nulls)
      })
  }

  protected final def queryOrganization: Rule1[LogicalPlan =>
      LogicalPlan] = rule {
    (ORDER ~ BY ~ ordering ~> ((o: Seq[SortOrder]) =>
      (l: LogicalPlan) => Sort(o, global = true, l)) |
    SORT ~ BY ~ ordering ~ distributeBy.? ~> ((o: Seq[SortOrder], d: Any) =>
      (l: LogicalPlan) => Sort(o, global = false, d.asInstanceOf[Option[
          LogicalPlan => LogicalPlan]].map(_ (l)).getOrElse(l))) |
    distributeBy |
    CLUSTER ~ BY ~ (expression + commaSep) ~> ((e: Seq[Expression]) =>
      (l: LogicalPlan) => Sort(e.map(SortOrder(_, Ascending)), global = false,
        RepartitionByExpression(e, l)))).? ~
    (WINDOW ~ ((identifier ~ AS ~ windowSpec ~>
        ((id: String, w: WindowSpec) => id -> w)) + commaSep)).? ~
    ((LIMIT ~ expressionNoTokens) | fetchExpression).? ~> {
      (o: Any, w: Any, e: Any) => (l: LogicalPlan) =>
      val withOrder = o.asInstanceOf[Option[LogicalPlan => LogicalPlan]]
          .map(_ (l)).getOrElse(l)
      val window = w.asInstanceOf[Option[Seq[(String, WindowSpec)]]].map { ws =>
        val baseWindowMap = ws.toMap
        val windowMapView = baseWindowMap.mapValues {
          case WindowSpecReference(name) =>
            baseWindowMap.get(name) match {
              case Some(spec: WindowSpecDefinition) => spec
              case Some(_) => throw new ParseException(
                s"Window reference '$name' is not a window specification")
              case None => throw new ParseException(
                s"Cannot resolve window reference '$name'")
            }
          case spec: WindowSpecDefinition => spec
        }

        // Note that mapValues creates a view, so force materialization.
        WithWindowDefinition(windowMapView.map(identity), withOrder)
      }.getOrElse(withOrder)
      e.asInstanceOf[Option[Expression]].map(Limit(_, window)).getOrElse(window)
    }
  }

  protected final def fetchExpression: Rule1[Expression] = rule {
    FETCH ~ FIRST ~ expressionNoTokens ~ ((ROW|ROWS) ~ ONLY) ~> ((f: Expression) => f)
  }

  protected final def distributeBy: Rule1[LogicalPlan => LogicalPlan] = rule {
    DISTRIBUTE ~ BY ~ (expression + commaSep) ~> ((e: Seq[Expression]) =>
      (l: LogicalPlan) => RepartitionByExpression(e, l))
  }

  protected final def windowSpec: Rule1[WindowSpec] = rule {
    '(' ~ ws ~ ((PARTITION | DISTRIBUTE | CLUSTER) ~ BY ~ (expression +
        commaSep)).? ~ ((ORDER | SORT) ~ BY ~ ordering).? ~ windowFrame.? ~ ')' ~
        ws ~> ((p: Any, o: Any, w: Any) =>
      WindowSpecDefinition(
        p.asInstanceOf[Option[Seq[Expression]]].getOrElse(Nil),
        o.asInstanceOf[Option[Seq[SortOrder]]].getOrElse(Nil),
        w.asInstanceOf[Option[SpecifiedWindowFrame]]
          .getOrElse(UnspecifiedFrame))) |
    identifier ~> WindowSpecReference
  }

  protected final def windowFrame: Rule1[SpecifiedWindowFrame] = rule {
    (RANGE ~> (() => RangeFrame) | ROWS ~> (() => RowFrame)) ~ (
        BETWEEN ~ frameBound ~ AND ~ frameBound ~> ((t: FrameType,
            s: FrameBoundary, e: FrameBoundary) => SpecifiedWindowFrame(t, s, e)) |
        frameBound ~> ((t: FrameType, s: FrameBoundary) =>
          SpecifiedWindowFrame(t, s, CurrentRow))
    )
  }

  protected final def frameBound: Rule1[FrameBoundary] = rule {
    UNBOUNDED ~ (
        PRECEDING ~> (() => UnboundedPreceding) |
        FOLLOWING ~> (() => UnboundedFollowing)
    ) |
    CURRENT ~ ROW ~> (() => CurrentRow) |
    integral ~ (
        PRECEDING ~> ((num: String) => ValuePreceding(num.toInt)) |
        FOLLOWING ~> ((num: String) => ValueFollowing(num.toInt))
    )
  }

  protected final def tableValuedFunctionExpressions: Rule1[Seq[Expression]] = rule {
    '(' ~ ws ~ (expression + commaSep).? ~ ')' ~>
      ((e: Any) => e.asInstanceOf[Option[Vector[Expression]]] match {
        case Some(ve) => ve
        case _ => Nil
      })
  }

  protected final def relationWithExternal: Rule1[LogicalPlan] = rule {
    ((relationFactor | inlineTable) ~ tableValuedFunctionExpressions.?) ~>
        ((lp: LogicalPlan, se: Any) => {
      se.asInstanceOf[Option[Seq[Expression]]] match {
        case None => lp
        case Some(exprs) =>
          val ur = lp.asInstanceOf[UnresolvedRelation]
          val fname = org.apache.spark.sql.collection.Utils.toLowerCase(
            ur.tableIdentifier.identifier)
          UnresolvedTableValuedFunction(fname, exprs)
      }
    })
  }

  protected final def relation: Rule1[LogicalPlan] = rule {
    relationWithExternal ~ (
        join. + ~> ((r1: LogicalPlan, joins: Any) => joins.asInstanceOf[
            Seq[JoinRuleType]].foldLeft(r1) { case (lhs, (jt, rhs, cond)) =>
          Join(lhs, rhs, joinType = jt.getOrElse(Inner), cond)
        }) |
        MATCH.asInstanceOf[Rule[LogicalPlan :: HNil, LogicalPlan :: HNil]]
    )
  }

  protected final def relations: Rule1[LogicalPlan] = rule {
    (relation + commaSep) ~ lateralView.* ~> ((joins: Seq[LogicalPlan], views: Any) => {
      val from = if (joins.size == 1) joins.head
      else joins.tail.foldLeft(joins.head) {
        case (lhs, rel) => Join(lhs, rel, Inner, None)
      }
      views.asInstanceOf[Seq[LogicalPlan => LogicalPlan]].foldLeft(from) {
        case (child, view) => view(child)
      }
    })
  }

  protected final def keyWhenThenElse: Rule1[WhenElseType] = rule {
    expression ~ (WHEN ~ expression ~ THEN ~ expression ~> ((w: Expression,
        t: Expression) => (w, t))). + ~ (ELSE ~ expression).? ~ END ~>
        ((key: Expression, altPart: Any, elsePart: Any) =>
          (altPart.asInstanceOf[Seq[(Expression, Expression)]].map(
            e => EqualTo(key, e._1) -> e._2), elsePart).asInstanceOf[WhenElseType])
  }

  protected final def whenThenElse: Rule1[WhenElseType] = rule {
    (WHEN ~ expression ~ THEN ~ expression ~> ((w: Expression,
        t: Expression) => (w, t))). + ~ (ELSE ~ expression).? ~ END ~>
        ((altPart: Any, elsePart: Any) =>
          (altPart, elsePart).asInstanceOf[WhenElseType])
  }

  protected final def foldableFunctionsExpressionHandler(exprs: Seq[Expression],
      fnName: String): Seq[Expression] = if (!_isPreparePhase) {
    Constant.FOLDABLE_FUNCTIONS.get(fnName) match {
      case null => exprs
      case args if args.length == 0 =>
        // disable plan caching for these functions
        session.planCaching = false
        exprs
      case args =>
        exprs.indices.map(index => exprs(index) match {
          case l: TokenizedLiteral if Ints.contains(args, index) ||
              // all args          // all odd args
              (args(0) == -10) || (args(0) == -1 && (index & 0x1) == 1) ||
              // all even args
              (args(0) == -2 && (index & 0x1) == 0) =>
            removeIfParamLiteralFromContext(l)
            newLiteral(l.value, l.dataType)
          case e => e
        })
    }
  } else exprs

  protected final def primary: Rule1[Expression] = rule {
    intervalLiteral |
    identifier ~ (
      ('.' ~ identifier).? ~ '(' ~ ws ~ (
        '*' ~ ws ~ ')' ~ ws ~> ((n1: String, n2: Option[String]) =>
          if (n1.equalsIgnoreCase("COUNT") && n2.isEmpty) {
            AggregateExpression(Count(Literal(1, IntegerType)),
              mode = Complete, isDistinct = false)
          } else {
            val n2str = if (n2.isEmpty) "" else s".${n2.get}"
            throw new ParseException(s"invalid expression $n1$n2str(*)")
          }) |
          (DISTINCT ~ push(true)).? ~ (expression * commaSep) ~ ')' ~ ws ~
            (OVER ~ windowSpec).? ~> { (n1: String, n2: Any, d: Any, e: Any, w: Any) =>
            val f2 = n2.asInstanceOf[Option[String]]
            val udfName = f2.fold(new FunctionIdentifier(n1))(new FunctionIdentifier(_, Some(n1)))
            val allExprs = e.asInstanceOf[Seq[Expression]]
            val exprs = foldableFunctionsExpressionHandler(allExprs, n1)
            val function = if (d.asInstanceOf[Option[Boolean]].isEmpty) {
              UnresolvedFunction(udfName, exprs, isDistinct = false)
            } else if (udfName.funcName.equalsIgnoreCase("COUNT")) {
              aggregate.Count(exprs).toAggregateExpression(isDistinct = true)
            } else {
              UnresolvedFunction(udfName, exprs, isDistinct = true)
            }
            w.asInstanceOf[Option[WindowSpec]] match {
              case None => function
              case Some(spec: WindowSpecDefinition) =>
                WindowExpression(function, spec)
              case Some(ref: WindowSpecReference) =>
                UnresolvedWindowExpression(function, ref)
            }
          }
        ) |
        '.' ~ ws ~ (
            identifier. +('.' ~ ws) ~> ((i1: String, rest: Any) =>
              UnresolvedAttribute(i1 +: rest.asInstanceOf[Seq[String]])) |
            (identifier ~ '.' ~ ws).* ~ '*' ~ ws ~> ((i1: String, rest: Any) =>
              UnresolvedStar(Option(i1 +: rest.asInstanceOf[Seq[String]])))
        ) |
        MATCH ~> UnresolvedAttribute.quoted _
    ) |
    literal | paramLiteralQuestionMark |
    '{' ~ FN ~ ws ~ functionIdentifier ~ '(' ~ (expression * commaSep) ~ ')' ~ ws ~ '}' ~ ws ~> {
      (fn: FunctionIdentifier, e: Any) =>
        val allExprs = e.asInstanceOf[Seq[Expression]].toList
        val exprs = foldableFunctionsExpressionHandler(allExprs, fn.funcName)
        fn match {
          case f if f.funcName.equalsIgnoreCase("TIMESTAMPADD") =>
            assert(exprs.length == 3)
            assert(exprs.head.isInstanceOf[UnresolvedAttribute] &&
                exprs.head.asInstanceOf[UnresolvedAttribute].name.equals("SQL_TSI_DAY"))
            DateAdd(exprs(2), exprs(1))
          case f => UnresolvedFunction(f, exprs, isDistinct = false)
        }
    } |
    CAST ~ '(' ~ ws ~ expression ~ AS ~ dataType ~ ')' ~ ws ~> (Cast(_, _)) |
    CASE ~ (
        whenThenElse ~> (s => CaseWhen(s._1, s._2)) |
        keyWhenThenElse ~> (s => CaseWhen(s._1, s._2))
    ) |
    EXISTS ~ '(' ~ ws ~ query ~ ')' ~ ws ~> (Exists(_)) |
    CURRENT_DATE ~> CurrentDate |
    CURRENT_TIMESTAMP ~> CurrentTimestamp |
    '(' ~ ws ~ (
        (expression + commaSep) ~ ')' ~ ws ~> ((exprs: Seq[Expression]) =>
          if (exprs.length == 1) exprs.head else CreateStruct(exprs)
        ) |
        query ~ ')' ~ ws ~> { (plan: LogicalPlan) =>
          session.planCaching = false // never cache scalar subquery plans
          ScalarSubquery(plan)
        }
    ) |
    signedPrimary |
    '~' ~ ws ~ expression ~> BitwiseNot
  }

  protected final def signedPrimary: Rule1[Expression] = rule {
    capture(plusOrMinus) ~ ws ~ primary ~> ((s: String, e: Expression) =>
      if (s.charAt(0) == '-') UnaryMinus(e) else e)
  }

  protected final def baseExpression: Rule1[Expression] = rule {
    '*' ~ ws ~> (() => UnresolvedStar(None)) |
    primary
  }

  protected def select: Rule1[LogicalPlan] = rule {
    SELECT ~ (DISTINCT ~ push(true)).? ~
    TOKENIZE_BEGIN ~ (namedExpression + commaSep) ~ TOKENIZE_END ~
    (FROM ~ relations).? ~
    TOKENIZE_BEGIN ~ (WHERE ~ expression).? ~
    groupBy.? ~
    (HAVING ~ expression).? ~
    queryOrganization ~ TOKENIZE_END ~> { (d: Any, p: Any, f: Any, w: Any, g: Any, h: Any,
        q: LogicalPlan => LogicalPlan) =>
      val base = f match {
        case Some(plan) => plan.asInstanceOf[LogicalPlan]
        case _ => OneRowRelation
      }
      val withFilter = w match {
        case Some(expr) => Filter(expr.asInstanceOf[Expression], base)
        case _ => base
      }
      val expressions = p.asInstanceOf[Seq[Expression]].map {
        case ne: NamedExpression => ne
        case e => UnresolvedAlias(e)
      }
      val gr = g.asInstanceOf[Option[(Seq[Expression], Seq[Seq[Expression]], String)]]
      val withProjection = gr match {
        case Some(x) => x._3 match {
          // group by cols with rollup
          case "ROLLUP" => Aggregate(Seq(Rollup(x._1)), expressions, withFilter)
          // group by cols with cube
          case "CUBE" => Aggregate(Seq(Cube(x._1)), expressions, withFilter)
          // group by cols with grouping sets()()
          case "GROUPINGSETS" => extractGroupingSet(withFilter, expressions, x._1, x._2)
          // just "group by cols"
          case _ => Aggregate(x._1, expressions, withFilter)
        }
        case _ => Project(expressions, withFilter)
      }
      val withDistinct = d match {
        case None => withProjection
        case Some(_) => Distinct(withProjection)
      }
      val withHaving = h match {
        case None => withDistinct
        case Some(expr) => Filter(expr.asInstanceOf[Expression], withDistinct)
      }
      q(withHaving)
    }
  }

  protected final def select2: Rule1[LogicalPlan] = rule {
    select | ('(' ~ ws ~ select ~ ')' ~ ws)
  }

  protected final def select1: Rule1[LogicalPlan] = rule {
    select2 | inlineTable
  }

  protected final def query: Rule1[LogicalPlan] = rule {
    select1.named("select") ~ (
        UNION ~ (
            ALL ~ select1.named("select") ~>
                ((q1: LogicalPlan, q2: LogicalPlan) => Union(q1, q2)) |
            DISTINCT.? ~ select1.named("select") ~>
                ((q1: LogicalPlan, q2: LogicalPlan) => Distinct(Union(q1, q2)))
        ) |
        INTERSECT ~ select1.named("select") ~>
            ((q1: LogicalPlan, q2: LogicalPlan) => Intersect(q1, q2)) |
        EXCEPT ~ select1.named("select") ~>
            ((q1: LogicalPlan, q2: LogicalPlan) => Except(q1, q2))
    ).*
  }

  // TODO: remove once planner allows for null padding for different number
  // of columns being inserted/put either with inlineTable or subselect
  protected final def subSelectQuery: Rule1[LogicalPlan] = rule {
    select2.named("select") ~ (
      UNION ~ (
        ALL ~ select2.named("select") ~>
          ((q1: LogicalPlan, q2: LogicalPlan) => Union(q1, q2)) |
          DISTINCT.? ~ select2.named("select") ~>
            ((q1: LogicalPlan, q2: LogicalPlan) => Distinct(Union(q1, q2)))
        ) |
        INTERSECT ~ select2.named("select") ~>
          ((q1: LogicalPlan, q2: LogicalPlan) => Intersect(q1, q2)) |
        EXCEPT ~ select2.named("select") ~>
          ((q1: LogicalPlan, q2: LogicalPlan) => Except(q1, q2))
      ).*
  }

  protected final def lateralView: Rule1[LogicalPlan => LogicalPlan] = rule {
    LATERAL ~ VIEW ~ (OUTER ~ push(true)).? ~ functionIdentifier ~ '(' ~ ws ~
        (expression * commaSep) ~ ')' ~ ws ~ identifier ~ (AS.? ~ (identifier + commaSep)).? ~>
        ((o: Any, functionName: FunctionIdentifier, e: Any, tableName: String,
            cols: Any) => (child: LogicalPlan) => {
          val expressions = e.asInstanceOf[Seq[Expression]]
          val columnNames = cols.asInstanceOf[Option[Seq[String]]] match {
            case Some(s) => s.map(UnresolvedAttribute.apply)
            case None => Nil
          }
          Generate(UnresolvedGenerator(functionName, expressions), join = true,
            outer = o.asInstanceOf[Option[Boolean]].isDefined, Some(tableName),
            columnNames, child)
        })
  }

  protected final def insert: Rule1[LogicalPlan] = rule {
    INSERT ~ ((OVERWRITE ~ push(true)) | (INTO ~ push(false))) ~
    TABLE.? ~ relationFactor ~ subSelectQuery ~> ((o: Boolean, r: LogicalPlan,
        s: LogicalPlan) => new Insert(r, Map.empty[String,
        Option[String]], s, OverwriteOptions(o), ifNotExists = false))
  }

  protected final def put: Rule1[LogicalPlan] = rule {
    PUT ~ INTO ~ TABLE.? ~ relationFactor ~ subSelectQuery ~> PutIntoTable
  }

  protected final def update: Rule1[LogicalPlan] = rule {
    UPDATE ~ relationFactor ~ SET ~ TOKENIZE_BEGIN ~ (((identifier + ('.' ~ ws)) ~
        '=' ~ ws ~ expression ~> ((cols: Seq[String], e: Expression) =>
      UnresolvedAttribute(cols) -> e)) + commaSep) ~ TOKENIZE_END ~
        (FROM ~ relations).? ~ (WHERE ~ TOKENIZE_BEGIN ~ expression ~ TOKENIZE_END).? ~>
        ((t: Any, updateExprs: Seq[(UnresolvedAttribute,
            Expression)], relations : Any, whereExpr: Any) => {
          val table = t.asInstanceOf[LogicalPlan]
          val base = relations match {
            case Some(plan) => plan.asInstanceOf[LogicalPlan]
            case _ => table
          }
          val withFilter = whereExpr match {
            case Some(expr) => Filter(expr.asInstanceOf[Expression], base)
            case _ => base
          }
          val (updateColumns, updateExpressions) = updateExprs.unzip
          Update(table, withFilter, Nil, updateColumns, updateExpressions)
        })
  }

  protected final def delete: Rule1[LogicalPlan] = rule {
    DELETE ~ FROM ~ relationFactor ~
        (WHERE ~ TOKENIZE_BEGIN ~ expression ~ TOKENIZE_END).? ~>
        ((t: Any, whereExpr: Any) => {
          val base = t.asInstanceOf[LogicalPlan]
          val child = whereExpr match {
            case None => base
            case Some(w) => Filter(w.asInstanceOf[Expression], base)
          }
          Delete(base, child, Nil)
        })
  }

  protected final def ctes: Rule1[LogicalPlan] = rule {
    WITH ~ ((identifier ~ AS.? ~ '(' ~ ws ~ query ~ ')' ~ ws ~>
        ((id: String, p: LogicalPlan) => (id, p))) + commaSep) ~
        (query | insert) ~> ((r: Seq[(String, LogicalPlan)], s: LogicalPlan) =>
        With(s, r.map(ns => (ns._1, SubqueryAlias(ns._1, ns._2, None)))))
  }

  protected def dmlOperation: Rule1[LogicalPlan] = rule {
    (INSERT ~ INTO | PUT ~ INTO) ~ tableIdentifier ~
        ANY.* ~> ((r: TableIdentifier) => DMLExternalTable(r,
        UnresolvedRelation(r), input.sliceString(0, input.length)))
  }

  private var tokenize = false

  private var canTokenize = false

  protected final def TOKENIZE_BEGIN: Rule0 = rule {
    MATCH ~> (() => tokenize = SnappySession.tokenize && canTokenize)
  }

  protected final def TOKENIZE_END: Rule0 = rule {
    MATCH ~> (() => tokenize = false)
  }

  protected final def ENABLE_TOKENIZE: Rule0 = rule {
    MATCH ~> (() => canTokenize = true)
  }

  protected final def DISABLE_TOKENIZE: Rule0 = rule {
    MATCH ~> (() => canTokenize = false)
  }

  override protected def start: Rule1[LogicalPlan] = rule {
    (ENABLE_TOKENIZE ~ (query.named("select") | insert | put | update | delete | ctes)) |
        (DISABLE_TOKENIZE ~ (dmlOperation | ddl | set | cache | uncache | desc))
  }

  final def parse[T](sqlText: String, parseRule: => Try[T]): T = session.synchronized {
    session.clearQueryData()
    session.sessionState.clearExecutionData()
    caseSensitive = session.sessionState.conf.caseSensitiveAnalysis
    parseSQL(sqlText, parseRule)
  }

  protected def parseSQL[T](sqlText: String, parseRule: => Try[T]): T = {
    this.input = sqlText
    val plan = parseRule match {
      case Success(p) => p
      case Failure(e: ParseError) =>
        throw new ParseException(formatError(e, new ErrorFormatter(
          showTraces = Property.ParserTraceError.get(session.sessionState.conf))))
      case Failure(e) =>
        throw new ParseException(e.toString, Some(e))
    }
    if (!queryHints.isEmpty) {
      session.queryHints.putAll(queryHints)
    }
    plan
  }

  protected def newInstance(): SnappyParser = new SnappyParser(session)
}
