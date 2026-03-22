package com.example.kotlin

import com.kubefn.api.FnContextAware
import com.kubefn.api.FnContext
import com.kubefn.api.KubeFnHandler
import com.kubefn.api.KubeFnRequest
import com.kubefn.api.KubeFnResponse
import com.kubefn.api.FnRoute
import com.kubefn.api.FnGroup
/**
 * Data transformation pipeline that reads results published by other
 * functions via HeapExchange and merges them into a unified view.
 *
 * This demonstrates the zero-copy capability of KubeFn's shared heap:
 * objects written by [RecommendationFunction] and [AnalyticsFunction]
 * are read here without serialisation — even if those functions were
 * written in Java or Scala, the JVM object reference is the same.
 *
 * Showcases Kotlin's functional operators, sealed classes, and
 * extension functions for pipeline-style transformations.
 */
@FnRoute(path = "/transform", methods = ["POST"])
@FnGroup("kotlin-showcase")
class TransformFunction : KubeFnHandler, FnContextAware {

    private lateinit var ctx: FnContext

    override fun setContext(context: FnContext) {
        ctx = context
    }

    // ── Pipeline model ───────────────────────────────────────────────

    sealed class TransformStep {
        data class Enrich(val field: String, val value: String) : TransformStep()
        data class Filter(val predicate: String) : TransformStep()
        data class MapValue(val source: String, val target: String) : TransformStep()
        data class Aggregate(val operation: String, val field: String) : TransformStep()
    }

    data class TransformedRecord(
        val source: String,
        val key: String,
        val value: String,
        val transformations: List<String>
    )

    data class TransformResult(
        val recordCount: Int,
        val records: List<TransformedRecord>,
        val heapKeysRead: List<String>,
        val transformedAt: Long
    )

    // ── Extension functions for pipeline readability ──────────────────

    private fun String.maskSensitive(): String =
        if (length > 4) "${take(2)}${"*".repeat(length - 4)}${takeLast(2)}" else this

    private fun <T> List<T>.partitionIndexed(predicate: (Int, T) -> Boolean): Pair<List<T>, List<T>> {
        val matching = mutableListOf<T>()
        val rest = mutableListOf<T>()
        forEachIndexed { i, item -> if (predicate(i, item)) matching.add(item) else rest.add(item) }
        return matching to rest
    }

    // ── Handler ──────────────────────────────────────────────────────

    override fun handle(request: KubeFnRequest): KubeFnResponse {
        val log = ctx.logger()
        val heap = ctx.heap()
        val userId = request.queryParam("userId").orElse("default-user")

        log.info("Starting transformation pipeline for user=$userId")

        val records = mutableListOf<TransformedRecord>()
        val heapKeysRead = mutableListOf<String>()

        // ── Stage 1: Read recommendations from heap (zero-copy) ──────

        val recommendationKey = "recommendations:$userId"
        heap.get(recommendationKey, RecommendationFunction.RecommendationResult::class.java)
            .ifPresent { result ->
                heapKeysRead += recommendationKey
                log.info("Read ${result.recommendations.size} recommendations from heap (zero-copy)")

                result.recommendations
                    .filter { it.score > 0.3 }
                    .mapNotNull { rec ->
                        rec.takeIf { it.productName.isNotBlank() }?.let {
                            TransformedRecord(
                                source = "recommendations",
                                key = it.productId,
                                value = "${it.productName} [score=${String.format("%.3f", it.score)}]",
                                transformations = listOf("filtered(score>0.3)", "formatted")
                            )
                        }
                    }
                    .also { records.addAll(it) }
            }

        // ── Stage 2: Read analytics from heap (zero-copy) ────────────

        val analyticsKey = "analytics:latest"
        heap.get(analyticsKey, AnalyticsFunction.AnalyticsResult::class.java)
            .ifPresent { result ->
                heapKeysRead += analyticsKey
                log.info("Read analytics with ${result.metrics.size} metric groups from heap (zero-copy)")

                result.metrics
                    .asSequence()
                    .filter { it.count >= 1 }
                    .map { metric ->
                        TransformedRecord(
                            source = "analytics",
                            key = "metric:${metric.eventType}",
                            value = "count=${metric.count}, avgMs=${String.format("%.1f", metric.avgDurationMs)}, " +
                                    "p95Ms=${metric.p95DurationMs}, users=${metric.uniqueUsers}",
                            transformations = listOf("filtered(count>=1)", "flattened")
                        )
                    }
                    .toList()
                    .also { records.addAll(it) }
            }

        // ── Stage 3: Apply cross-source transformations ──────────────

        val (highValue, standard) = records.partitionIndexed { _, record ->
            record.source == "recommendations" && record.value.contains("score=0.9")
        }

        val enrichedRecords = (highValue.map { record ->
            record.copy(
                transformations = record.transformations + "enriched(high-value)"
            )
        } + standard).sortedBy { it.key }

        // ── Publish result ───────────────────────────────────────────

        val transformResult = TransformResult(
            recordCount = enrichedRecords.size,
            records = enrichedRecords,
            heapKeysRead = heapKeysRead,
            transformedAt = System.currentTimeMillis()
        )

        heap.publish("transform:$userId", transformResult, TransformResult::class.java)
        log.info("Published ${enrichedRecords.size} transformed records to heap")

        val body = buildString {
            append("""{"recordCount":${transformResult.recordCount},""")
            append(""""heapKeysRead":${heapKeysRead.joinToString(",", "[", "]") { "\"$it\"" }},""")
            append(""""transformedAt":${transformResult.transformedAt},""")
            append(""""records":[""")
            enrichedRecords.forEachIndexed { i, r ->
                if (i > 0) append(",")
                append("""{"source":"${r.source}",""")
                append(""""key":"${r.key}",""")
                append(""""value":"${r.value}",""")
                append(""""transformations":${r.transformations.joinToString(",", "[", "]") { "\"$it\"" }}}""")
            }
            append("]}")
        }

        return KubeFnResponse.ok(body)
    }
}
