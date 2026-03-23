package com.kubefn.examples.usecases.sidecars;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * RateLimiterFunction — Replaces Envoy/NGINX rate-limiting sidecar containers.
 *
 * BEFORE (traditional K8s):
 *   - Each pod has a rate-limiting sidecar or a shared rate-limit service Deployment
 *   - Sidecar approach: N pods x 32-64MB = wasted memory for rate limit state
 *   - Shared service approach: extra network hop for every request (adds 1-5ms latency)
 *   - Rate limit state stored in Redis — another Deployment to manage
 *   - Complex to configure per-client limits across multiple sidecars
 *
 * AFTER (KubeFn):
 *   - One function using FnCache for token bucket counters — in-process, no Redis
 *   - All functions call this before processing requests — zero network hop
 *   - Per-client limits stored in FnCache — fast, local, no serialization
 *   - Rate limit state is shared across all functions in the organism
 */
@FnRoute(path = "/ratelimit/check", methods = {"POST"})
@FnGroup("platform-security")
public class RateLimiterFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(RateLimiterFunction.class.getName());

    // Default rate limits — configurable per tier
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;
    private static final int PREMIUM_REQUESTS_PER_MINUTE = 300;
    private static final int INTERNAL_REQUESTS_PER_MINUTE = 1000;
    private static final long WINDOW_MS = 60_000; // 1 minute

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String clientId = request.queryParam("client-id").orElse("");
        String tier = request.queryParam("tier").orElse("");

        if (clientId == null || clientId.isEmpty()) {
            return KubeFnResponse.status(400).body(Map.of("error", "client-id required"));
        }

        int limit = resolveLimit(tier);
        String bucketKey = "ratelimit:" + clientId;
        long now = Instant.now().toEpochMilli();

        // Read current bucket state from FnCache
        TokenBucket bucket = ctx.cache().get(bucketKey, TokenBucket.class).orElse(null);

        if (bucket == null) {
            // First request from this client — initialize bucket
            bucket = new TokenBucket(clientId, limit - 1, now, limit);
            ctx.cache().put(bucketKey, bucket);

            return allowed(clientId, bucket);
        }

        // Check if the window has rolled over — refill tokens
        long elapsed = now - bucket.windowStart();
        if (elapsed >= WINDOW_MS) {
            // New window — reset tokens
            bucket = new TokenBucket(clientId, bucket.maxTokens() - 1, now, bucket.maxTokens());
            ctx.cache().put(bucketKey, bucket);

            return allowed(clientId, bucket);
        }

        // Same window — check if tokens remain
        if (bucket.tokensRemaining() <= 0) {
            long retryAfterMs = WINDOW_MS - elapsed;

            LOG.warning(String.format("Rate limit exceeded: client=%s, tier=%s, retry_after=%dms",
                    clientId, tier, retryAfterMs));

            // Publish rate limit event to heap for monitoring
            ctx.heap().publish("ratelimit:exceeded:" + clientId, new RateLimitEvent(
                    clientId, tier, now, retryAfterMs));

            return KubeFnResponse.status(429).body(Map.of(
                    "error", "rate_limit_exceeded",
                    "clientId", clientId,
                    "limit", limit,
                    "retryAfterMs", retryAfterMs,
                    "windowMs", WINDOW_MS
            ));
        }

        // Consume a token
        bucket = new TokenBucket(clientId, bucket.tokensRemaining() - 1,
                bucket.windowStart(), bucket.maxTokens());
        ctx.cache().put(bucketKey, bucket);

        return allowed(clientId, bucket);
    }

    private KubeFnResponse allowed(String clientId, TokenBucket bucket) {
        return KubeFnResponse.ok(Map.of(
                "allowed", true,
                "clientId", clientId,
                "remaining", bucket.tokensRemaining(),
                "limit", bucket.maxTokens(),
                "windowMs", WINDOW_MS
        ));
    }

    private int resolveLimit(String tier) {
        if (tier == null) return DEFAULT_REQUESTS_PER_MINUTE;
        return switch (tier) {
            case "premium" -> PREMIUM_REQUESTS_PER_MINUTE;
            case "internal" -> INTERNAL_REQUESTS_PER_MINUTE;
            default -> DEFAULT_REQUESTS_PER_MINUTE;
        };
    }

    /** Token bucket stored in FnCache — no Redis, no network. */
    public record TokenBucket(String clientId, int tokensRemaining,
                              long windowStart, int maxTokens) {}

    public record RateLimitEvent(String clientId, String tier,
                                 long timestamp, long retryAfterMs) {}
}
