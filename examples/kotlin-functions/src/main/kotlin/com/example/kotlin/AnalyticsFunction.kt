package com.example.kotlin

import com.kubefn.api.FnContextAware
import com.kubefn.api.FnContext
import com.kubefn.api.KubeFnHandler
import com.kubefn.api.KubeFnRequest
import com.kubefn.api.KubeFnResponse
import com.kubefn.api.FnRoute
import com.kubefn.api.FnGroup
/**
 * Real-time analytics aggregation function.
 *
 * Accepts a JSON array of event objects, computes summary metrics
 * (counts, averages, percentiles), and publishes the aggregated
 * result to HeapExchange for zero-copy consumption by other functions.
 *
 * Demonstrates Kotlin sequences, destructuring, and scope functions.
 */
@FnRoute(path = "/analytics", methods = ["POST"])
@FnGroup("kotlin-showcase")
class AnalyticsFunction : KubeFnHandler, FnContextAware {

    private lateinit var ctx: FnContext

    override fun setContext(context: FnContext) {
        ctx = context
    }

    // ── Domain models ────────────────────────────────────────────────

    data class Event(
        val type: String,
        val timestamp: Long,
        val durationMs: Long,
        val userId: String,
        val metadata: Map<String, String> = emptyMap()
    )

    data class MetricSummary(
        val eventType: String,
        val count: Int,
        val avgDurationMs: Double,
        val p50DurationMs: Long,
        val p95DurationMs: Long,
        val p99DurationMs: Long,
        val uniqueUsers: Int
    )

    data class AnalyticsResult(
        val totalEvents: Int,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val metrics: List<MetricSummary>,
        val computedAt: Long
    )

    // ── Parsing ──────────────────────────────────────────────────────

    private fun parseEvents(body: String): List<Event> {
        // Lightweight manual parsing — avoids requiring a JSON library
        // at compile time. In production, Jackson on the shared classloader
        // would be preferred.
        val events = mutableListOf<Event>()
        val entries = body.trim().removeSurrounding("[", "]").split("},")

        for (raw in entries) {
            val entry = raw.trim().removeSurrounding("{", "}")
            if (entry.isBlank()) continue

            val fields = entry.split(",")
                .associate { kv ->
                    val (k, v) = kv.split(":", limit = 2).map { it.trim().removeSurrounding("\"") }
                    k to v
                }

            events += Event(
                type = fields["type"] ?: "unknown",
                timestamp = fields["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis(),
                durationMs = fields["durationMs"]?.toLongOrNull() ?: 0L,
                userId = fields["userId"] ?: "anonymous"
            )
        }
        return events
    }

    // ── Percentile computation via sequences ─────────────────────────

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    // ── Handler ──────────────────────────────────────────────────────

    override fun handle(request: KubeFnRequest): KubeFnResponse {
        val body = request.bodyAsString()
        if (body.isNullOrBlank()) {
            return KubeFnResponse.error("""{"error":"Request body must be a JSON array of events"}""")
        }

        val log = ctx.logger()
        val events = parseEvents(body)

        if (events.isEmpty()) {
            return KubeFnResponse.error("""{"error":"No valid events found in request body"}""")
        }

        log.info("Processing ${events.size} events for analytics aggregation")

        // Group by event type and compute metrics using sequences and destructuring
        val metrics: List<MetricSummary> = events
            .asSequence()
            .groupBy { it.type }
            .map { (eventType, group) ->
                val durations = group.asSequence()
                    .map { it.durationMs }
                    .sorted()
                    .toList()

                val uniqueUsers = group.asSequence()
                    .map { it.userId }
                    .distinct()
                    .count()

                MetricSummary(
                    eventType = eventType,
                    count = group.size,
                    avgDurationMs = durations.average(),
                    p50DurationMs = percentile(durations, 50.0),
                    p95DurationMs = percentile(durations, 95.0),
                    p99DurationMs = percentile(durations, 99.0),
                    uniqueUsers = uniqueUsers
                )
            }
            .sortedByDescending { it.count }
            .toList()

        // Destructure the time window from the event list
        val (windowStart, windowEnd) = events
            .asSequence()
            .map { it.timestamp }
            .let { timestamps ->
                (timestamps.minOrNull() ?: 0L) to (timestamps.maxOrNull() ?: 0L)
            }

        val result = AnalyticsResult(
            totalEvents = events.size,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            metrics = metrics,
            computedAt = System.currentTimeMillis()
        )

        // Publish to HeapExchange — any function on this group can read zero-copy
        ctx.heap().publish("analytics:latest", result, AnalyticsResult::class.java)
        log.info("Published analytics result with ${metrics.size} metric groups to heap")

        val body2 = buildString {
            append("""{"totalEvents":${result.totalEvents},""")
            append(""""windowStartMs":${result.windowStartMs},""")
            append(""""windowEndMs":${result.windowEndMs},""")
            append(""""computedAt":${result.computedAt},""")
            append(""""metrics":[""")
            metrics.forEachIndexed { i, m ->
                if (i > 0) append(",")
                append("""{"eventType":"${m.eventType}",""")
                append(""""count":${m.count},""")
                append(""""avgDurationMs":${String.format("%.2f", m.avgDurationMs)},""")
                append(""""p50DurationMs":${m.p50DurationMs},""")
                append(""""p95DurationMs":${m.p95DurationMs},""")
                append(""""p99DurationMs":${m.p99DurationMs},""")
                append(""""uniqueUsers":${m.uniqueUsers}}""")
            }
            append("]}")
        }

        return KubeFnResponse.ok(body2)
    }
}
