package com.example.scala

import com.kubefn.api._

import java.util.Optional

/**
 * Orchestrator that invokes the stream processor and rule engine,
 * then assembles results from HeapExchange into a unified response.
 *
 * Demonstrates:
 *  - Function-to-function invocation via FnContext.getFunction
 *  - Zero-copy heap reads across Scala functions (and potentially
 *    across language boundaries with Kotlin/Java functions)
 *  - Timing metadata for observability
 */
@FnRoute(path = "/scala/demo", methods = Array("GET"))
@FnGroup("scala-showcase")
class ScalaShowcaseFunction extends KubeFnHandler with FnContextAware {

  private var ctx: FnContext = _

  override def setContext(context: FnContext): Unit = {
    ctx = context
  }

  // ── Timing helper ──────────────────────────────────────────────────

  case class StageResult(name: String, durationMs: Long, success: Boolean, detail: String)

  private def timed[T](name: String)(block: => T): (Option[T], StageResult) = {
    val start = System.nanoTime()
    try {
      val result = block
      val elapsed = (System.nanoTime() - start) / 1000000
      (Some(result), StageResult(name, elapsed, success = true, "ok"))
    } catch {
      case ex: Exception =>
        val elapsed = (System.nanoTime() - start) / 1000000
        (None, StageResult(name, elapsed, success = false, Option(ex.getMessage).getOrElse("unknown error")))
    }
  }

  // ── Synthetic request builder ──────────────────────────────────────

  private def syntheticRequest(body: String, httpMethod: String, httpPath: String): KubeFnRequest =
    new KubeFnRequest(httpMethod, httpPath, "",
      java.util.Map.of(), java.util.Map.of(),
      if (body != null && body.nonEmpty) body.getBytes("UTF-8") else Array.emptyByteArray)

  // ── Handler ────────────────────────────────────────────────────────

  override def handle(request: KubeFnRequest): KubeFnResponse = {
    val log = ctx.logger()
    val heap = ctx.heap()
    val orchestrationStart = System.currentTimeMillis()
    val stages = scala.collection.mutable.ListBuffer.empty[StageResult]

    log.info(s"Scala showcase orchestration starting — group=${ctx.groupName()}, revision=${ctx.revisionId()}")

    // ── Stage 1: Stream processing ───────────────────────────────────

    val sampleEvents =
      s"""[
        {"eventType":"info","source":"us-east-1","timestamp":${System.currentTimeMillis() - 4000},"payload":"metrics"},
        {"eventType":"error","source":"eu-west-1","timestamp":${System.currentTimeMillis() - 3000},"payload":"alert"},
        {"eventType":"warning","source":"ap-south-1","timestamp":${System.currentTimeMillis() - 2000},"payload":"notice"},
        {"eventType":"info","source":"us-west-2","timestamp":${System.currentTimeMillis() - 1000},"payload":"log"},
        {"eventType":"error","source":"eu-central-1","timestamp":${System.currentTimeMillis()},"payload":"critical"}
      ]"""

    val (streamResponse, streamStage) = timed("stream-processor") {
      val fn = ctx.getFunction(classOf[StreamProcessorFunction])
      fn.handle(syntheticRequest(sampleEvents, "POST", "/stream/process"))
    }
    stages += streamStage

    // ── Stage 2: Rule evaluation ─────────────────────────────────────

    val sampleRuleInputs =
      """[
        {"category":"premium","value":"8500","tags":"audit-required"},
        {"category":"restricted","value":"2000","tags":"internal-only"},
        {"category":"standard","value":"3200","tags":""},
        {"category":"standard","value":"15000","tags":"pii;high-traffic"}
      ]"""

    val (rulesResponse, rulesStage) = timed("rule-engine") {
      val fn = ctx.getFunction(classOf[RuleEngineFunction])
      fn.handle(syntheticRequest(sampleRuleInputs, "POST", "/rules/evaluate"))
    }
    stages += rulesStage

    // ── Read results from heap (zero-copy) ───────────────────────────

    val streamResultOpt = {
      val opt = heap.get("stream:processed", classOf[StreamProcessorFunction#StreamResult])
      if (opt.isPresent) Some(opt.get()) else None
    }

    val rulesResultOpt = {
      val opt = heap.get("rules:result", classOf[RuleEngineFunction#RuleEngineResult])
      if (opt.isPresent) Some(opt.get()) else None
    }

    val totalDurationMs = System.currentTimeMillis() - orchestrationStart

    log.info(s"Scala showcase completed in ${totalDurationMs}ms — ${stages.count(_.success)}/${stages.size} stages succeeded")

    // ── Build response ───────────────────────────────────────────────

    val responseBody = {
      val sb = new StringBuilder
      sb.append("""{"showcase":"scala",""")
      sb.append(s""""group":"${ctx.groupName()}",""")
      sb.append(s""""revision":"${ctx.revisionId()}",""")

      // Timing metadata
      sb.append(s""""_meta":{"totalDurationMs":$totalDurationMs,"stages":[""")
      stages.zipWithIndex.foreach { case (s, i) =>
        if (i > 0) sb.append(",")
        sb.append(s"""{"name":"${s.name}","durationMs":${s.durationMs},"success":${s.success},"detail":"${s.detail}"}""")
      }
      sb.append("]},")

      // Heap summary
      sb.append(""""heapSummary":{""")
      sb.append(s""""streamEventsProcessed":${streamResultOpt.map(_.totalReceived).getOrElse(0)},""")
      sb.append(s""""streamValidCount":${streamResultOpt.map(_.validCount).getOrElse(0)},""")
      sb.append(s""""rulesEvaluated":${rulesResultOpt.map(_.totalInputs).getOrElse(0)},""")
      sb.append(s""""rulesSummary":{${rulesResultOpt.map(_.summary.map { case (k, v) => s""""$k":$v""" }.mkString(",")).getOrElse("")}}""")
      sb.append("},")

      // Cross-language note
      sb.append(""""crossLanguageNote":"Objects on HeapExchange are JVM references. A Kotlin or Java function in the same group can read stream:processed and rules:result zero-copy.",""")

      // Inline stage responses
      sb.append(""""results":{""")
      sb.append(s""""streamProcessor":${streamResponse.map(_.toString).getOrElse("null")},""")
      sb.append(s""""ruleEngine":${rulesResponse.map(_.toString).getOrElse("null")}""")
      sb.append("}}")

      sb.toString()
    }

    KubeFnResponse.ok(responseBody)
  }
}
