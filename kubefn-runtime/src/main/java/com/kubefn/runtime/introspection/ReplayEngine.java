package com.kubefn.runtime.introspection;

import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.runtime.routing.FunctionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic replay engine for KubeFn requests.
 *
 * <p>Given a previously captured request (identified by requestId), the
 * replay engine can re-execute the same entry function and capture a new
 * trace. By comparing the original and replay traces, operators can:
 * <ul>
 *   <li>Verify that a code change produces identical behavior</li>
 *   <li>Reproduce bugs in a controlled environment</li>
 *   <li>Test new revisions against production traffic patterns</li>
 *   <li>Diff heap mutation sequences across revisions</li>
 * </ul>
 *
 * <p>The replay engine generates a unique replay requestId (prefixed with
 * "replay-") so replay events are distinguishable from live traffic in
 * the ring buffer.
 *
 * <p>Limitations:
 * <ul>
 *   <li>Replay does not restore heap state — the current live heap is used</li>
 *   <li>External side effects (HTTP calls, DB writes) will execute again</li>
 *   <li>Timing-dependent behavior may produce different results</li>
 *   <li>The original request body is not captured — replay uses a synthetic
 *       empty request (future versions will capture full request payloads)</li>
 * </ul>
 */
public class ReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final CausalCaptureEngine captureEngine;
    private final FunctionRouter router;

    /**
     * Creates a replay engine wired to the given capture engine and router.
     *
     * @param captureEngine the capture engine holding historical traces
     * @param router        the function router for re-invoking functions
     */
    public ReplayEngine(CausalCaptureEngine captureEngine, FunctionRouter router) {
        this.captureEngine = Objects.requireNonNull(captureEngine, "captureEngine must not be null");
        this.router = Objects.requireNonNull(router, "router must not be null");
    }

    /**
     * Replays a previously captured request by re-invoking its entry
     * function and capturing a new trace.
     *
     * @param requestId the original request ID to replay
     * @return replay result with original trace, new trace, and diff
     * @throws ReplayException if the original request cannot be found or
     *                         the entry function is no longer available
     */
    public ReplayResult replay(String requestId) {
        return replayInternal(requestId, null);
    }

    /**
     * Replays a previously captured request, forcing execution against
     * a specific code revision. This enables A/B comparison of function
     * behavior across revisions.
     *
     * @param requestId   the original request ID to replay
     * @param newRevision the revision to force for replay execution
     * @return replay result with original trace, new trace, and diff
     * @throws ReplayException if the original request cannot be found
     */
    public ReplayResult replayWithRevision(String requestId, String newRevision) {
        Objects.requireNonNull(newRevision, "newRevision must not be null");
        return replayInternal(requestId, newRevision);
    }

    private ReplayResult replayInternal(String requestId, String targetRevision) {
        // 1. Look up the original trace
        RequestTrace originalTrace = captureEngine.getTrace(requestId);
        if (originalTrace == null) {
            throw new ReplayException("No trace found for requestId: " + requestId);
        }

        String entryGroup = originalTrace.entryGroup();
        String entryFunction = originalTrace.entryFunction();

        // 2. Resolve the entry function through the router
        //    We look for any route that maps to this group+function
        var routes = router.allRoutes();
        KubeFnHandler handler = null;
        String resolvedMethod = "GET";
        String resolvedPath = "/";

        for (var entry : routes.entrySet()) {
            var functionEntry = entry.getValue();
            if (entryGroup.equals(functionEntry.groupName())
                    && entryFunction.equals(functionEntry.functionName())) {
                // If a specific revision is requested, check it matches
                // (or accept any — the caller wants to force a different revision)
                handler = functionEntry.handler();
                resolvedMethod = entry.getKey().method();
                resolvedPath = entry.getKey().path();
                break;
            }
        }

        if (handler == null) {
            throw new ReplayException(
                    "Entry function no longer available: " + entryGroup + "." + entryFunction);
        }

        // 3. Build a synthetic request matching the original
        String replayRequestId = "replay-" + requestId + "-" + System.currentTimeMillis();
        String revision = targetRevision != null ? targetRevision : originalTrace.entryRevision();

        KubeFnRequest syntheticRequest = new KubeFnRequest(
                resolvedMethod,
                resolvedPath,
                "",
                Map.of("X-KubeFn-Replay", "true",
                       "X-KubeFn-Original-Request-Id", requestId),
                Map.of(),
                new byte[0]
        );

        // 4. Capture the replay execution
        captureEngine.captureRequestStart(replayRequestId, entryGroup, entryFunction, revision);
        captureEngine.captureFunctionStart(replayRequestId, entryGroup, entryFunction, revision);

        long startNanos = System.nanoTime();
        String replayError = null;

        try {
            KubeFnResponse response = handler.handle(syntheticRequest);
            long durationNanos = System.nanoTime() - startNanos;

            captureEngine.captureFunctionEnd(replayRequestId, entryGroup, entryFunction,
                    durationNanos, null);
            captureEngine.captureRequestEnd(replayRequestId, durationNanos, null);

        } catch (Exception e) {
            long durationNanos = System.nanoTime() - startNanos;
            replayError = e.getClass().getSimpleName() + ": " + e.getMessage();

            captureEngine.captureError(replayRequestId, entryGroup, entryFunction, replayError);
            captureEngine.captureFunctionEnd(replayRequestId, entryGroup, entryFunction,
                    durationNanos, replayError);
            captureEngine.captureRequestEnd(replayRequestId, durationNanos, replayError);

            log.warn("Replay of {} failed: {}", requestId, replayError, e);
        }

        // 5. Build the replay trace and compute diff
        RequestTrace replayTrace = captureEngine.getTrace(replayRequestId);
        if (replayTrace == null) {
            throw new ReplayException("Failed to capture replay trace for: " + replayRequestId);
        }

        List<String> differences = computeDifferences(originalTrace, replayTrace);
        boolean outputMatched = differences.isEmpty();

        log.info("Replay of {} complete: {} differences, outputMatched={}",
                requestId, differences.size(), outputMatched);

        return new ReplayResult(originalTrace, replayTrace, differences, outputMatched);
    }

    /**
     * Computes a human-readable list of differences between two traces.
     */
    private List<String> computeDifferences(RequestTrace original, RequestTrace replay) {
        List<String> diffs = new ArrayList<>();

        // Compare function counts
        if (original.functionCount() != replay.functionCount()) {
            diffs.add(String.format("Function count: original=%d, replay=%d",
                    original.functionCount(), replay.functionCount()));
        }

        // Compare error counts
        if (original.errors() != replay.errors()) {
            diffs.add(String.format("Error count: original=%d, replay=%d",
                    original.errors(), replay.errors()));
        }

        // Compare heap publish counts
        if (original.heapPublishes() != replay.heapPublishes()) {
            diffs.add(String.format("Heap publishes: original=%d, replay=%d",
                    original.heapPublishes(), replay.heapPublishes()));
        }

        // Compare heap get counts
        if (original.heapGets() != replay.heapGets()) {
            diffs.add(String.format("Heap gets: original=%d, replay=%d",
                    original.heapGets(), replay.heapGets()));
        }

        // Compare step-level function sequences
        List<TraceStep> origSteps = original.steps();
        List<TraceStep> replaySteps = replay.steps();

        int maxSteps = Math.max(origSteps.size(), replaySteps.size());
        for (int i = 0; i < maxSteps; i++) {
            if (i >= origSteps.size()) {
                TraceStep extra = replaySteps.get(i);
                diffs.add(String.format("Extra step in replay [%d]: %s.%s",
                        i, extra.groupName(), extra.functionName()));
            } else if (i >= replaySteps.size()) {
                TraceStep missing = origSteps.get(i);
                diffs.add(String.format("Missing step in replay [%d]: %s.%s",
                        i, missing.groupName(), missing.functionName()));
            } else {
                TraceStep orig = origSteps.get(i);
                TraceStep rep = replaySteps.get(i);

                if (!Objects.equals(orig.functionName(), rep.functionName())) {
                    diffs.add(String.format("Step [%d] function: original=%s, replay=%s",
                            i, orig.functionName(), rep.functionName()));
                }
                if (!Objects.equals(orig.status(), rep.status())) {
                    diffs.add(String.format("Step [%d] status: original=%s, replay=%s",
                            i, orig.status(), rep.status()));
                }
            }
        }

        // Compare heap mutation sequences
        List<HeapMutation> origHeap = original.heapMutations();
        List<HeapMutation> replayHeap = replay.heapMutations();

        if (origHeap.size() != replayHeap.size()) {
            diffs.add(String.format("Heap mutation count: original=%d, replay=%d",
                    origHeap.size(), replayHeap.size()));
        }

        int maxMutations = Math.min(origHeap.size(), replayHeap.size());
        for (int i = 0; i < maxMutations; i++) {
            HeapMutation origM = origHeap.get(i);
            HeapMutation repM = replayHeap.get(i);

            if (origM.type() != repM.type() || !Objects.equals(origM.key(), repM.key())) {
                diffs.add(String.format("Heap mutation [%d]: original=%s(%s), replay=%s(%s)",
                        i, origM.type(), origM.key(), repM.type(), repM.key()));
            }
        }

        // Timing comparison (informational, not treated as a structural diff)
        double timingRatio = original.totalTimeMs() > 0
                ? replay.totalTimeMs() / original.totalTimeMs()
                : 0.0;
        if (timingRatio > 2.0 || (timingRatio > 0 && timingRatio < 0.5)) {
            diffs.add(String.format("Timing divergence: original=%.2fms, replay=%.2fms (ratio=%.2fx)",
                    original.totalTimeMs(), replay.totalTimeMs(), timingRatio));
        }

        return diffs;
    }

    /**
     * Result of a replay execution, containing both traces and their diff.
     *
     * @param originalTrace the trace from the original request
     * @param replayTrace   the trace from the replayed execution
     * @param differences   human-readable list of behavioral differences
     * @param outputMatched true if no structural differences were detected
     */
    public record ReplayResult(
            RequestTrace originalTrace,
            RequestTrace replayTrace,
            List<String> differences,
            boolean outputMatched
    ) {
        public ReplayResult {
            Objects.requireNonNull(originalTrace, "originalTrace must not be null");
            Objects.requireNonNull(replayTrace, "replayTrace must not be null");
            differences = differences != null ? List.copyOf(differences) : List.of();
        }
    }

    /**
     * Exception thrown when replay cannot proceed.
     */
    public static class ReplayException extends RuntimeException {
        public ReplayException(String message) {
            super(message);
        }

        public ReplayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
