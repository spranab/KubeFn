package com.example.scala

import com.kubefn.api._

import java.util.Optional
import scala.jdk.OptionConverters._
import scala.util.{Try, Success, Failure}

/**
 * Stream processing function that accepts a batch of events,
 * validates, enriches, and aggregates them using idiomatic Scala.
 *
 * Demonstrates:
 *  - Case classes for immutable domain models
 *  - Pattern matching for event dispatch
 *  - For-comprehensions over Option/Either
 *  - Publishing results to HeapExchange for cross-language zero-copy access
 */
@FnRoute(path = "/stream/process", methods = Array("POST"))
@FnGroup("scala-showcase")
class StreamProcessorFunction extends KubeFnHandler with FnContextAware {

  private var ctx: FnContext = _

  override def setContext(context: FnContext): Unit = {
    ctx = context
  }

  // ── Domain models ──────────────────────────────────────────────────

  case class RawEvent(
    eventType: String,
    source: String,
    timestamp: Long,
    payload: Map[String, String]
  )

  sealed trait ProcessedEvent {
    def eventType: String
    def timestamp: Long
    def processingNote: String
  }

  case class ValidEvent(
    eventType: String,
    source: String,
    timestamp: Long,
    enrichedPayload: Map[String, String],
    processingNote: String
  ) extends ProcessedEvent

  case class InvalidEvent(
    eventType: String,
    timestamp: Long,
    reason: String,
    processingNote: String
  ) extends ProcessedEvent

  case class StreamResult(
    totalReceived: Int,
    validCount: Int,
    invalidCount: Int,
    eventTypeCounts: Map[String, Int],
    processedEvents: List[ProcessedEvent],
    processedAt: Long
  )

  // ── Parsing ────────────────────────────────────────────────────────

  private def parseEvents(body: String): List[RawEvent] = {
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
          eventType <- fields.get("eventType")
          source    <- fields.get("source").orElse(Some("unknown"))
        } yield RawEvent(
          eventType = eventType,
          source = source,
          timestamp = fields.get("timestamp").flatMap(s => Try(s.toLong).toOption).getOrElse(System.currentTimeMillis()),
          payload = fields -- Set("eventType", "source", "timestamp")
        )
      }
    }
  }

  // ── Processing pipeline ────────────────────────────────────────────

  private def validate(event: RawEvent): Either[String, RawEvent] = event match {
    case e if e.eventType.isBlank          => Left("Empty event type")
    case e if e.timestamp <= 0             => Left(s"Invalid timestamp: ${e.timestamp}")
    case e if e.source == "blocked-source" => Left(s"Blocked source: ${e.source}")
    case e                                 => Right(e)
  }

  private def enrich(event: RawEvent): ValidEvent = {
    val enrichments = Map(
      "processedBy" -> "scala-stream-processor",
      "region"      -> inferRegion(event.source),
      "priority"    -> inferPriority(event.eventType)
    )

    ValidEvent(
      eventType = event.eventType,
      source = event.source,
      timestamp = event.timestamp,
      enrichedPayload = event.payload ++ enrichments,
      processingNote = s"Enriched with ${enrichments.size} fields"
    )
  }

  private def inferRegion(source: String): String = source match {
    case s if s.startsWith("us-") => "north-america"
    case s if s.startsWith("eu-") => "europe"
    case s if s.startsWith("ap-") => "asia-pacific"
    case _                        => "global"
  }

  private def inferPriority(eventType: String): String = eventType match {
    case "error" | "alert"    => "high"
    case "warning"            => "medium"
    case "info" | "page_view" => "low"
    case _                    => "normal"
  }

  // ── Handler ────────────────────────────────────────────────────────

  override def handle(request: KubeFnRequest): KubeFnResponse = {
    val body = request.bodyAsString()
    if (body == null || body.isBlank) {
      return KubeFnResponse.error("""{"error":"Request body must be a JSON array of events"}""")
    }

    val log = ctx.logger()
    val rawEvents = parseEvents(body)

    log.info(s"Stream processor received ${rawEvents.size} events")

    val processed: List[ProcessedEvent] = rawEvents.map { event =>
      validate(event) match {
        case Right(valid) => enrich(valid)
        case Left(reason) => InvalidEvent(
          eventType = event.eventType,
          timestamp = event.timestamp,
          reason = reason,
          processingNote = "Rejected during validation"
        )
      }
    }

    val (valid, invalid) = processed.partition(_.isInstanceOf[ValidEvent])

    // Aggregate counts per event type using for-comprehension style
    val typeCounts: Map[String, Int] = (for {
      event <- valid
      validEvent = event.asInstanceOf[ValidEvent]
    } yield validEvent.eventType)
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap

    val result = StreamResult(
      totalReceived = rawEvents.size,
      validCount = valid.size,
      invalidCount = invalid.size,
      eventTypeCounts = typeCounts,
      processedEvents = processed,
      processedAt = System.currentTimeMillis()
    )

    // Publish to HeapExchange — Kotlin or Java functions can read this zero-copy
    ctx.heap().publish("stream:processed", result, classOf[StreamResult])
    log.info(s"Published stream result: ${valid.size} valid, ${invalid.size} invalid to heap")

    val responseBody = {
      val sb = new StringBuilder
      sb.append(s"""{"totalReceived":${result.totalReceived},""")
      sb.append(s""""validCount":${result.validCount},""")
      sb.append(s""""invalidCount":${result.invalidCount},""")
      sb.append(s""""processedAt":${result.processedAt},""")
      sb.append(""""eventTypeCounts":{""")
      sb.append(typeCounts.map { case (k, v) => s""""$k":$v""" }.mkString(","))
      sb.append("},")
      sb.append(""""processedEvents":[""")
      processed.zipWithIndex.foreach { case (event, i) =>
        if (i > 0) sb.append(",")
        event match {
          case v: ValidEvent =>
            sb.append(s"""{"status":"valid","eventType":"${v.eventType}","source":"${v.source}",""")
            sb.append(s""""priority":"${v.enrichedPayload.getOrElse("priority", "normal")}",""")
            sb.append(s""""note":"${v.processingNote}"}""")
          case iv: InvalidEvent =>
            sb.append(s"""{"status":"invalid","eventType":"${iv.eventType}",""")
            sb.append(s""""reason":"${iv.reason}","note":"${iv.processingNote}"}""")
        }
      }
      sb.append("]}")
      sb.toString()
    }

    KubeFnResponse.ok(responseBody)
  }
}
