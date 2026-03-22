package com.example.scala

import com.kubefn.api._

import scala.util.Try

/**
 * Business rule engine that evaluates rules defined as Scala partial
 * functions against incoming data.
 *
 * Demonstrates:
 *  - Partial functions as composable rule definitions
 *  - Pattern matching for rule dispatch and data extraction
 *  - Reading context from HeapExchange (e.g., stream processor results)
 *  - Trait-based rule abstraction
 */
@FnRoute(path = "/rules/evaluate", methods = Array("POST"))
@FnGroup("scala-showcase")
class RuleEngineFunction extends KubeFnHandler with FnContextAware {

  private var ctx: FnContext = _

  override def setContext(context: FnContext): Unit = {
    ctx = context
  }

  // ── Domain models ──────────────────────────────────────────────────

  case class RuleInput(
    category: String,
    value: Double,
    tags: Set[String],
    metadata: Map[String, String]
  )

  sealed trait RuleOutcome
  case class Approved(rule: String, reason: String)             extends RuleOutcome
  case class Rejected(rule: String, reason: String)             extends RuleOutcome
  case class Flagged(rule: String, reason: String, level: String) extends RuleOutcome

  case class EvaluationResult(
    inputCategory: String,
    outcomes: List[RuleOutcome],
    heapContextUsed: Boolean,
    evaluatedAt: Long
  )

  case class RuleEngineResult(
    totalInputs: Int,
    evaluations: List[EvaluationResult],
    summary: Map[String, Int],
    evaluatedAt: Long
  )

  // ── Rules as partial functions ─────────────────────────────────────

  /** High-value threshold rule */
  private val highValueRule: PartialFunction[RuleInput, RuleOutcome] = {
    case input if input.value > 10000.0 =>
      Flagged("high-value", s"Value ${input.value} exceeds threshold 10000", "warning")
    case input if input.value > 50000.0 =>
      Rejected("high-value", s"Value ${input.value} exceeds hard limit 50000")
  }

  /** Category-based approval rule */
  private val categoryRule: PartialFunction[RuleInput, RuleOutcome] = {
    case input if input.category == "premium" =>
      Approved("category-premium", "Premium category auto-approved")
    case input if input.category == "restricted" =>
      Rejected("category-restricted", "Restricted category denied")
    case input if input.category == "standard" && input.value <= 5000.0 =>
      Approved("category-standard", s"Standard category within limit (value=${input.value})")
  }

  /** Tag-based compliance rule */
  private val complianceRule: PartialFunction[RuleInput, RuleOutcome] = {
    case input if input.tags.contains("audit-required") =>
      Flagged("compliance-audit", "Requires manual audit review", "info")
    case input if input.tags.contains("pii") =>
      Flagged("compliance-pii", "Contains PII data — additional handling required", "critical")
    case input if input.tags.contains("internal-only") && input.category != "internal" =>
      Rejected("compliance-internal", "Internal-only tag on non-internal category")
  }

  /** Context-aware rule that reads heap data from stream processor */
  private def contextAwareRule(streamEventCount: Option[Int]): PartialFunction[RuleInput, RuleOutcome] = {
    case input if streamEventCount.exists(_ > 100) && input.tags.contains("high-traffic") =>
      Flagged("context-traffic", s"High traffic detected (${streamEventCount.getOrElse(0)} events) with high-traffic tag", "warning")
    case input if streamEventCount.exists(_ > 0) =>
      Approved("context-active", s"Stream context active with ${streamEventCount.getOrElse(0)} events")
  }

  /** Compose all rules — apply each that matches and collect outcomes */
  private def evaluateAllRules(input: RuleInput, streamEventCount: Option[Int]): List[RuleOutcome] = {
    val rules = List(
      highValueRule,
      categoryRule,
      complianceRule,
      contextAwareRule(streamEventCount)
    )

    rules.flatMap { rule =>
      rule.lift(input).toList
    } match {
      case Nil => List(Approved("default", s"No rules matched — default approval for category '${input.category}'"))
      case outcomes => outcomes
    }
  }

  // ── Parsing ────────────────────────────────────────────────────────

  private def parseInputs(body: String): List[RuleInput] = {
    val entries = body.trim.stripPrefix("[").stripSuffix("]").split("},")

    entries.toList.flatMap { raw =>
      val cleaned = raw.trim.stripPrefix("{").stripSuffix("}")
      if (cleaned.isBlank) None
      else {
        val fields = cleaned.split(",").map { kv =>
          val parts = kv.split(":", 2)
          parts(0).trim.stripPrefix("\"").stripSuffix("\"") ->
            parts(1).trim.stripPrefix("\"").stripSuffix("\"")
        }.toMap

        for {
          category <- fields.get("category")
        } yield RuleInput(
          category = category,
          value = fields.get("value").flatMap(s => Try(s.toDouble).toOption).getOrElse(0.0),
          tags = fields.get("tags").map(_.split(";").map(_.trim).toSet).getOrElse(Set.empty),
          metadata = fields -- Set("category", "value", "tags")
        )
      }
    }
  }

  // ── Handler ────────────────────────────────────────────────────────

  override def handle(request: KubeFnRequest): KubeFnResponse = {
    val body = request.bodyAsString()
    if (body == null || body.isBlank) {
      return KubeFnResponse.error("""{"error":"Request body must be a JSON array of rule inputs"}""")
    }

    val log = ctx.logger()
    val inputs = parseInputs(body)

    if (inputs.isEmpty) {
      return KubeFnResponse.error("""{"error":"No valid rule inputs found"}""")
    }

    log.info(s"Rule engine evaluating ${inputs.size} inputs")

    // Read stream processor context from heap (zero-copy, potentially written by Scala or Kotlin)
    val streamEventCount: Option[Int] = {
      val opt = ctx.heap().get("stream:processed", classOf[StreamProcessorFunction#StreamResult])
      if (opt.isPresent) {
        log.info("Read stream processor context from heap (zero-copy)")
        Some(opt.get().totalReceived)
      } else {
        None
      }
    }

    val evaluations: List[EvaluationResult] = inputs.map { input =>
      val outcomes = evaluateAllRules(input, streamEventCount)
      EvaluationResult(
        inputCategory = input.category,
        outcomes = outcomes,
        heapContextUsed = streamEventCount.isDefined,
        evaluatedAt = System.currentTimeMillis()
      )
    }

    // Build summary by outcome type using pattern matching
    val summary: Map[String, Int] = evaluations
      .flatMap(_.outcomes)
      .groupBy {
        case _: Approved => "approved"
        case _: Rejected => "rejected"
        case _: Flagged  => "flagged"
      }
      .view
      .mapValues(_.size)
      .toMap

    val result = RuleEngineResult(
      totalInputs = inputs.size,
      evaluations = evaluations,
      summary = summary,
      evaluatedAt = System.currentTimeMillis()
    )

    // Publish to heap for downstream functions
    ctx.heap().publish("rules:result", result, classOf[RuleEngineResult])
    log.info(s"Published rule engine result to heap: ${summary.mkString(", ")}")

    val responseBody = {
      val sb = new StringBuilder
      sb.append(s"""{"totalInputs":${result.totalInputs},""")
      sb.append(s""""evaluatedAt":${result.evaluatedAt},""")
      sb.append(""""summary":{""")
      sb.append(summary.map { case (k, v) => s""""$k":$v""" }.mkString(","))
      sb.append("},")
      sb.append(""""evaluations":[""")
      evaluations.zipWithIndex.foreach { case (eval, i) =>
        if (i > 0) sb.append(",")
        sb.append(s"""{"category":"${eval.inputCategory}","heapContextUsed":${eval.heapContextUsed},"outcomes":[""")
        eval.outcomes.zipWithIndex.foreach { case (outcome, j) =>
          if (j > 0) sb.append(",")
          outcome match {
            case Approved(rule, reason) =>
              sb.append(s"""{"status":"approved","rule":"$rule","reason":"$reason"}""")
            case Rejected(rule, reason) =>
              sb.append(s"""{"status":"rejected","rule":"$rule","reason":"$reason"}""")
            case Flagged(rule, reason, level) =>
              sb.append(s"""{"status":"flagged","rule":"$rule","reason":"$reason","level":"$level"}""")
          }
        }
        sb.append("]}")
      }
      sb.append("]}")
      sb.toString()
    }

    KubeFnResponse.ok(responseBody)
  }
}
