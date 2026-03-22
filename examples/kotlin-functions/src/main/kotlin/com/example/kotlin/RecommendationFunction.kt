package com.example.kotlin

import com.kubefn.api.FnContextAware
import com.kubefn.api.FnContext
import com.kubefn.api.KubeFnHandler
import com.kubefn.api.KubeFnRequest
import com.kubefn.api.KubeFnResponse
import com.kubefn.api.FnRoute
import com.kubefn.api.FnGroup
/**
 * Product recommendation engine showcasing idiomatic Kotlin with KubeFn.
 *
 * Builds a user preference vector, scores candidate products against it,
 * and publishes the ranked recommendations to HeapExchange so downstream
 * functions (in any JVM language) can read them zero-copy.
 */
@FnRoute(path = "/recommend", methods = ["GET", "POST"])
@FnGroup("kotlin-showcase")
class RecommendationFunction : KubeFnHandler, FnContextAware {

    private lateinit var ctx: FnContext

    override fun setContext(context: FnContext) {
        ctx = context
    }

    // ── Domain models ────────────────────────────────────────────────

    data class UserPreference(
        val category: String,
        val weight: Double
    )

    data class Product(
        val id: String,
        val name: String,
        val category: String,
        val tags: List<String>,
        val baseScore: Double
    )

    data class Recommendation(
        val productId: String,
        val productName: String,
        val score: Double,
        val reason: String
    )

    data class RecommendationResult(
        val userId: String,
        val recommendations: List<Recommendation>,
        val generatedAt: Long
    )

    // ── Catalogue & preference simulation ────────────────────────────

    private val catalogue = listOf(
        Product("p-101", "Kubernetes in Action",     "books",       listOf("devops", "cloud"),       0.85),
        Product("p-102", "Cloud-Native Laptop Stand", "accessories", listOf("ergonomic", "office"),   0.70),
        Product("p-103", "Container Monitoring SaaS", "services",    listOf("observability", "saas"), 0.90),
        Product("p-104", "Serverless Cookbook",        "books",       listOf("serverless", "cloud"),   0.80),
        Product("p-105", "Ergonomic Keyboard",        "accessories", listOf("ergonomic", "typing"),   0.75),
        Product("p-106", "CI/CD Pipeline Toolkit",    "services",    listOf("devops", "automation"),  0.88),
        Product("p-107", "Microservices Patterns",    "books",       listOf("architecture", "cloud"), 0.82),
        Product("p-108", "Noise-Cancelling Headset",  "accessories", listOf("audio", "office"),       0.72)
    )

    private fun buildPreferenceVector(userId: String): List<UserPreference> {
        // Simulate per-user preferences derived from historical behaviour.
        val hash = userId.hashCode().toLong().let { if (it < 0) -it else it }
        return listOf(
            UserPreference("books",       0.4 + (hash % 30) / 100.0),
            UserPreference("services",    0.3 + (hash % 20) / 100.0),
            UserPreference("accessories", 0.2 + (hash % 10) / 100.0)
        )
    }

    // ── Scoring ──────────────────────────────────────────────────────

    private fun Double.clamp(min: Double = 0.0, max: Double = 1.0): Double =
        coerceIn(min, max)

    private fun scoreProduct(product: Product, preferences: List<UserPreference>): Double {
        val categoryBoost = preferences
            .firstOrNull { it.category == product.category }
            ?.weight ?: 0.1

        val tagBonus = product.tags.count { it in setOf("cloud", "devops", "ergonomic") } * 0.05

        return (product.baseScore * categoryBoost + tagBonus).clamp()
    }

    private fun explainScore(product: Product, preferences: List<UserPreference>): String {
        val pref = preferences.firstOrNull { it.category == product.category }
        return pref?.let { "Matches preference '${it.category}' (weight ${it.weight})" }
            ?: "General suggestion"
    }

    // ── Handler ──────────────────────────────────────────────────────

    override fun handle(request: KubeFnRequest): KubeFnResponse {
        val userId = request.queryParam("userId")
            .orElse(null)
            ?: return KubeFnResponse.error("""{"error":"Missing query parameter: userId"}""")

        val log = ctx.logger()
        log.info("Generating recommendations for user=$userId")

        val preferences = buildPreferenceVector(userId)

        val recommendations = catalogue
            .map { product ->
                val score = scoreProduct(product, preferences)
                Recommendation(
                    productId = product.id,
                    productName = product.name,
                    score = score,
                    reason = explainScore(product, preferences)
                )
            }
            .sortedByDescending { it.score }
            .take(5)

        val result = RecommendationResult(
            userId = userId,
            recommendations = recommendations,
            generatedAt = System.currentTimeMillis()
        ).apply {
            // Publish to HeapExchange so any downstream function (Kotlin, Scala,
            // or Java) can read the result zero-copy — no serialisation overhead.
            ctx.heap().publish("recommendations:$userId", this, RecommendationResult::class.java)
            log.info("Published ${recommendations.size} recommendations to heap for user=$userId")
        }

        val body = buildString {
            append("""{"userId":"${result.userId}",""")
            append(""""count":${result.recommendations.size},""")
            append(""""generatedAt":${result.generatedAt},""")
            append(""""recommendations":[""")
            result.recommendations.forEachIndexed { i, rec ->
                if (i > 0) append(",")
                append("""{"productId":"${rec.productId}",""")
                append(""""productName":"${rec.productName}",""")
                append(""""score":${String.format("%.4f", rec.score)},""")
                append(""""reason":"${rec.reason}"}""")
            }
            append("]}")
        }

        return KubeFnResponse.ok(body)
    }
}
