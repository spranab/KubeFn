package com.kubefn.examples.usecases.schedulers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * HealthMonitorFunction — Replaces dedicated health-check sidecar containers.
 *
 * BEFORE (traditional K8s):
 *   - Each microservice implements its own /health endpoint checking its own deps
 *   - Or a sidecar like Envoy health-checks external services independently
 *   - If 10 services all depend on the same DB, 10 containers are pinging it
 *   - Health status is siloed — Service A doesn't know Service B's DB is down
 *
 * AFTER (KubeFn):
 *   - One function checks all external dependencies every minute
 *   - Publishes health status to HeapExchange — ALL functions read it zero-copy
 *   - Before making a DB call, any function can check "deps:health" on the heap
 *   - One health check per dependency, not one per consumer
 */
@FnSchedule(cron = "0 * * * *", timeoutMs = 15000, skipIfRunning = true)
@FnRoute(path = "/admin/health", methods = {"GET"})
@FnGroup("platform-ops")
public class HealthMonitorFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(HealthMonitorFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        Map<String, DependencyHealth> results = new LinkedHashMap<>();
        long now = Instant.now().toEpochMilli();

        // Check each external dependency and record its status
        results.put("postgres", checkDatabase());
        results.put("redis", checkRedis());
        results.put("payment-api", checkExternalApi("https://api.stripe.com/v1/health"));
        results.put("email-service", checkExternalApi("https://api.sendgrid.com/v3/health"));
        results.put("search-index", checkElasticsearch());

        // Determine overall health
        boolean allHealthy = results.values().stream().allMatch(DependencyHealth::healthy);
        int degradedCount = (int) results.values().stream().filter(h -> !h.healthy()).count();

        // Publish to heap — every function in the organism can now check dependency
        // health before making external calls, avoiding cascading failures
        var status = new HealthStatus(now, allHealthy, results, degradedCount);
        ctx.heap().publish("deps:health", status);

        if (degradedCount > 0) {
            LOG.warning(String.format("Health check: %d/%d dependencies degraded",
                    degradedCount, results.size()));
        } else {
            LOG.info("Health check: all dependencies healthy");
        }

        return KubeFnResponse.ok(Map.of(
                "healthy", allHealthy,
                "dependencies", results,
                "checkedAt", now
        ));
    }

    // --- Simulated dependency checks (real impl would make actual connections) ---

    private DependencyHealth checkDatabase() {
        long latency = simulateCheck(5, 0.02);  // 2% failure rate
        return new DependencyHealth(latency >= 0, latency, "primary-rw");
    }

    private DependencyHealth checkRedis() {
        long latency = simulateCheck(2, 0.01);
        return new DependencyHealth(latency >= 0, latency, "cache-cluster");
    }

    private DependencyHealth checkExternalApi(String url) {
        long latency = simulateCheck(50, 0.05);
        return new DependencyHealth(latency >= 0, latency, url);
    }

    private DependencyHealth checkElasticsearch() {
        long latency = simulateCheck(10, 0.03);
        return new DependencyHealth(latency >= 0, latency, "search-cluster");
    }

    /** Simulates a health check with configurable latency and failure rate. */
    private long simulateCheck(long baseLatencyMs, double failureRate) {
        if (Math.random() < failureRate) {
            return -1; // simulated failure
        }
        return baseLatencyMs + (long) (Math.random() * baseLatencyMs);
    }

    public record DependencyHealth(boolean healthy, long latencyMs, String endpoint) {}
    public record HealthStatus(long timestamp, boolean allHealthy,
                               Map<String, DependencyHealth> dependencies, int degradedCount) {}
}
