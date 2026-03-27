package com.kubefn.runtime.replay;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides when to escalate from REFERENCE capture to full VALUE capture.
 *
 * <p>Triggers for VALUE capture:
 * <ul>
 *   <li>Function invocation failed (error/exception)</li>
 *   <li>Latency exceeded threshold (p99 anomaly)</li>
 *   <li>Function is on the watchlist</li>
 *   <li>First N invocations after a hot-swap (new revision)</li>
 *   <li>Adaptive sampling (1 in N for steady-state profiling)</li>
 * </ul>
 *
 * <p>NOT captured as VALUE:
 * <ul>
 *   <li>Normal successful invocations (REFERENCE only)</li>
 *   <li>Functions marked @NoCapture</li>
 *   <li>Payloads exceeding size limit</li>
 * </ul>
 */
public class CapturePolicy {

    private final Set<String> watchlist = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicLong> revisionInvocationCounts = new ConcurrentHashMap<>();
    private long latencyThresholdNanos = 100_000_000; // 100ms default
    private int postDeployCaptures = 50;              // first 50 after hot-swap
    private int samplingRate = 1000;                  // 1 in 1000 for steady state
    private int maxSnapshotBytes = 1024 * 1024;       // 1MB max per snapshot
    private final AtomicLong invocationCounter = new AtomicLong();

    /**
     * Should this invocation be captured at VALUE level?
     */
    public boolean shouldCaptureValue(
            String functionName,
            String revisionId,
            boolean failed,
            long durationNanos,
            int estimatedPayloadBytes
    ) {
        // Never capture oversized payloads
        if (estimatedPayloadBytes > maxSnapshotBytes) {
            return false;
        }

        // Always capture failures
        if (failed) {
            return true;
        }

        // Always capture latency anomalies
        if (durationNanos > latencyThresholdNanos) {
            return true;
        }

        // Always capture watchlisted functions
        if (watchlist.contains(functionName)) {
            return true;
        }

        // Capture first N invocations after hot-swap
        AtomicLong count = revisionInvocationCounts
                .computeIfAbsent(revisionId, k -> new AtomicLong());
        if (count.incrementAndGet() <= postDeployCaptures) {
            return true;
        }

        // Adaptive sampling for steady state
        return invocationCounter.incrementAndGet() % samplingRate == 0;
    }

    // ── Configuration ──

    public void addToWatchlist(String functionName) {
        watchlist.add(functionName);
    }

    public void removeFromWatchlist(String functionName) {
        watchlist.remove(functionName);
    }

    public void setLatencyThreshold(long nanos) {
        this.latencyThresholdNanos = nanos;
    }

    public void setPostDeployCaptures(int n) {
        this.postDeployCaptures = n;
    }

    public void setSamplingRate(int oneInN) {
        this.samplingRate = oneInN;
    }

    public void setMaxSnapshotBytes(int bytes) {
        this.maxSnapshotBytes = bytes;
    }

    /**
     * Reset revision counters (called on group unload).
     */
    public void resetRevision(String revisionId) {
        revisionInvocationCounts.remove(revisionId);
    }

    public Map<String, Object> status() {
        return Map.of(
                "watchlist", List.copyOf(watchlist),
                "latencyThresholdMs", latencyThresholdNanos / 1_000_000,
                "postDeployCaptures", postDeployCaptures,
                "samplingRate", "1:" + samplingRate,
                "maxSnapshotBytes", maxSnapshotBytes,
                "trackedRevisions", revisionInvocationCounts.size(),
                "totalInvocations", invocationCounter.get()
        );
    }
}
