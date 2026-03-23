package com.kubefn.examples.usecases.schedulers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MetricsAggregatorFunction — Replaces a Prometheus/StatsD sidecar + aggregation container.
 *
 * BEFORE (traditional K8s):
 *   - Each pod has a metrics sidecar (Prometheus exporter, StatsD agent)
 *   - A separate Deployment runs the aggregation pipeline (Prometheus server, Grafana)
 *   - Metrics flow: app -> sidecar -> network -> Prometheus -> network -> Grafana
 *   - Each hop adds latency, resource cost, and failure points
 *
 * AFTER (KubeFn):
 *   - Functions publish raw metrics counters to heap as they process requests
 *   - This aggregator reads them every minute, computes percentiles and rates
 *   - Publishes summary to heap — dashboard functions read it zero-copy
 *   - No sidecars, no network hops for internal metrics
 */
@FnSchedule(cron = "0 * * * *", timeoutMs = 10000)
@FnRoute(path = "/admin/metrics", methods = {"GET"})
@FnGroup("platform-ops")
public class MetricsAggregatorFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(MetricsAggregatorFunction.class.getName());
    private static final String METRICS_PREFIX = "metrics:";

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long now = Instant.now().toEpochMilli();
        Map<String, FunctionMetrics> perFunction = new HashMap<>();
        long totalRequests = 0;
        long totalErrors = 0;

        // Scan heap for raw metrics published by individual functions
        for (String key : ctx.heap().keys()) {
            if (!key.startsWith(METRICS_PREFIX)) {
                continue;
            }

            String functionName = key.substring(METRICS_PREFIX.length());
            ctx.heap().get(key, RawMetrics.class).ifPresent(raw -> {
                // Compute derived metrics: error rate, avg latency, p95, p99
                double errorRate = raw.requestCount() > 0
                        ? (double) raw.errorCount() / raw.requestCount()
                        : 0.0;
                double avgLatency = raw.requestCount() > 0
                        ? raw.totalLatencyMs() / (double) raw.requestCount()
                        : 0.0;

                perFunction.put(functionName, new FunctionMetrics(
                        raw.requestCount(), raw.errorCount(), errorRate,
                        avgLatency, raw.p95LatencyMs(), raw.p99LatencyMs(),
                        raw.lastRequestAt()
                ));
            });

            // Accumulate totals
            ctx.heap().get(key, RawMetrics.class).ifPresent(raw -> {
                // Tracked via local vars isn't possible with lambdas;
                // in production, use AtomicLong or accumulate outside lambda
            });
        }

        // Compute organism-wide totals
        for (FunctionMetrics fm : perFunction.values()) {
            totalRequests += fm.requestCount();
            totalErrors += fm.errorCount();
        }

        double overallErrorRate = totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0;

        var summary = new MetricsSummary(now, totalRequests, totalErrors,
                overallErrorRate, perFunction.size(), perFunction);

        // Publish aggregated summary — dashboard and alerting functions read this
        ctx.heap().publish("ops:metrics-summary", summary);

        LOG.info(String.format("Metrics aggregated: %d functions, %d total requests, %.2f%% error rate",
                perFunction.size(), totalRequests, overallErrorRate * 100));

        return KubeFnResponse.ok(Map.of(
                "timestamp", now,
                "totalRequests", totalRequests,
                "totalErrors", totalErrors,
                "errorRate", overallErrorRate,
                "functions", perFunction
        ));
    }

    /** Raw counters published by individual functions during request handling. */
    public record RawMetrics(long requestCount, long errorCount, double totalLatencyMs,
                             double p95LatencyMs, double p99LatencyMs, long lastRequestAt) {}

    /** Derived per-function metrics computed by this aggregator. */
    public record FunctionMetrics(long requestCount, long errorCount, double errorRate,
                                  double avgLatencyMs, double p95LatencyMs, double p99LatencyMs,
                                  long lastRequestAt) {}

    /** Organism-wide metrics summary published to heap. */
    public record MetricsSummary(long timestamp, long totalRequests, long totalErrors,
                                 double overallErrorRate, int functionCount,
                                 Map<String, FunctionMetrics> perFunction) {}
}
