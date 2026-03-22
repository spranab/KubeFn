package com.example.kotlin

import com.kubefn.api.*

/**
 * Kotlin showcase orchestrator. Calls all Kotlin functions,
 * reads results from HeapExchange, and returns a combined response.
 */
@FnRoute(path = "/kotlin/demo", methods = ["GET"])
@FnGroup("kotlin-showcase")
class KotlinShowcaseFunction : KubeFnHandler, FnContextAware {
    private lateinit var ctx: FnContext

    override fun setContext(context: FnContext) { ctx = context }

    override fun handle(request: KubeFnRequest): KubeFnResponse {
        val startNanos = System.nanoTime()
        val userId = request.queryParam("userId").orElse("demo-user")

        // Create a simple request to pass to sub-functions
        val subRequest = KubeFnRequest("GET", request.path(), "",
            mapOf(), mapOf("userId" to userId), ByteArray(0))

        // Stage 1: Recommendations
        val t1 = System.nanoTime()
        ctx.getFunction(RecommendationFunction::class.java).handle(subRequest)
        val d1 = (System.nanoTime() - t1) / 1_000_000.0

        // Stage 2: Analytics
        val eventsBody = """[{"type":"click","userId":"$userId"},{"type":"purchase","userId":"$userId"}]"""
        val analyticsRequest = KubeFnRequest("POST", "/analytics", "",
            mapOf(), mapOf(), eventsBody.toByteArray())
        val t2 = System.nanoTime()
        ctx.getFunction(AnalyticsFunction::class.java).handle(analyticsRequest)
        val d2 = (System.nanoTime() - t2) / 1_000_000.0

        // Stage 3: Transform (reads from heap zero-copy)
        val t3 = System.nanoTime()
        ctx.getFunction(TransformFunction::class.java).handle(subRequest)
        val d3 = (System.nanoTime() - t3) / 1_000_000.0

        // Read all results from HeapExchange — zero-copy
        val recommendations = ctx.heap().get("kotlin:recommendations", Map::class.java)
        val analytics = ctx.heap().get("kotlin:analytics", Map::class.java)
        val transformed = ctx.heap().get("kotlin:transformed", Map::class.java)

        val totalMs = (System.nanoTime() - startNanos) / 1_000_000.0

        return KubeFnResponse.ok(mapOf(
            "language" to "Kotlin",
            "recommendations" to recommendations.orElse(null),
            "analytics" to analytics.orElse(null),
            "transformed" to transformed.orElse(null),
            "_meta" to mapOf(
                "pipelineSteps" to 3,
                "totalTimeMs" to "%.3f".format(totalMs),
                "stages" to mapOf(
                    "recommendations" to "%.3f".format(d1) + "ms",
                    "analytics" to "%.3f".format(d2) + "ms",
                    "transform" to "%.3f".format(d3) + "ms"
                ),
                "zeroCopy" to true,
                "language" to "Kotlin",
                "note" to "All 3 Kotlin functions share the same JVM heap with zero serialization"
            )
        ))
    }
}
