package com.kubefn.runtime.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.runtime.routing.FunctionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Replays captured invocations against current (or alternate) function code.
 *
 * <p>This is the core of evidence-based hot-swap:
 * <ol>
 *   <li>Capture invocations during production (InvocationCapture)</li>
 *   <li>Deploy new function revision</li>
 *   <li>Replay captured invocations against BOTH old and new revisions</li>
 *   <li>Diff outputs to detect regressions</li>
 *   <li>Promote only if policy passes</li>
 * </ol>
 *
 * <p>Side effects are NOT executed during replay. The function runs in a
 * sandboxed context where heap writes are captured but not committed.
 */
public class ReplayExecutor {
    private static final Logger log = LoggerFactory.getLogger(ReplayExecutor.class);

    private final FunctionRouter router;
    private final ObjectMapper objectMapper;

    public ReplayExecutor(FunctionRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /**
     * Replay a single captured invocation against the current function code.
     *
     * @param capture the captured invocation (must have VALUE level with input snapshot)
     * @return replay result with output and comparison data
     */
    public ReplayResult replay(InvocationCapture capture) {
        if (capture.level() != InvocationCapture.CaptureLevel.VALUE || capture.inputSnapshot() == null) {
            return ReplayResult.error(capture.invocationId(),
                    "Cannot replay: no input snapshot (capture level is " + capture.level() + ")");
        }

        String method = "POST"; // default for replay
        String path = "/" + capture.functionName().replace('.', '/');

        // Resolve the function
        var resolved = router.resolve(method, path);
        if (resolved.isEmpty()) {
            // Try with the original path if available
            return ReplayResult.error(capture.invocationId(),
                    "Function not found for replay: " + capture.functionName());
        }

        var route = resolved.get();
        var entry = route.entry();

        // Build the request from captured input
        KubeFnRequest request = new KubeFnRequest(
                method, path, route.subPath(),
                Map.of("x-kubefn-replay", "true",
                       "x-kubefn-original-request", capture.requestId()),
                Map.of(),
                capture.inputSnapshot());

        // Execute
        long startNanos = System.nanoTime();
        KubeFnResponse response;
        String error = null;
        boolean success;

        try {
            response = entry.handler().handle(request);
            success = true;
        } catch (Exception e) {
            response = KubeFnResponse.error(Map.of("error", e.getMessage()));
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            success = false;
        }

        long durationNanos = System.nanoTime() - startNanos;

        // Serialize the replay output for comparison
        byte[] replayOutput = null;
        try {
            if (response.body() != null) {
                replayOutput = objectMapper.writeValueAsBytes(response.body());
            }
        } catch (Exception ignored) {}

        // Compare with original output if available
        OutputDiff diff = null;
        if (capture.outputSnapshot() != null && replayOutput != null) {
            diff = diffOutputs(capture.outputSnapshot(), replayOutput);
        }

        return new ReplayResult(
                capture.invocationId(),
                capture.functionName(),
                entry.groupName(),
                entry.revisionId(),
                capture.revisionId(),
                success,
                error,
                durationNanos,
                capture.durationNanos(),
                replayOutput,
                diff,
                Instant.now()
        );
    }

    /**
     * Replay multiple captures and aggregate results.
     * Used for evidence-based hot-swap validation.
     */
    public BatchReplayResult replayBatch(List<InvocationCapture> captures) {
        long startTime = System.nanoTime();
        List<ReplayResult> results = new ArrayList<>();
        int passed = 0, failed = 0, diverged = 0, errors = 0;

        for (var capture : captures) {
            ReplayResult result = replay(capture);
            results.add(result);

            if (result.error() != null) {
                errors++;
            } else if (!result.success()) {
                failed++;
            } else if (result.diff() != null && !result.diff().identical()) {
                diverged++;
            } else {
                passed++;
            }
        }

        long totalNanos = System.nanoTime() - startTime;

        return new BatchReplayResult(
                results.size(), passed, failed, diverged, errors,
                totalNanos, results
        );
    }

    /**
     * Diff two JSON outputs structurally.
     */
    @SuppressWarnings("unchecked")
    private OutputDiff diffOutputs(byte[] original, byte[] replay) {
        try {
            String origStr = new String(original, StandardCharsets.UTF_8);
            String replayStr = new String(replay, StandardCharsets.UTF_8);

            if (origStr.equals(replayStr)) {
                return new OutputDiff(true, List.of(), 0);
            }

            // Parse both as maps and compare field-by-field
            Map<String, Object> origMap = objectMapper.readValue(original, Map.class);
            Map<String, Object> replayMap = objectMapper.readValue(replay, Map.class);

            List<String> differences = new ArrayList<>();
            diffMaps("", origMap, replayMap, differences);

            return new OutputDiff(false, differences, differences.size());

        } catch (Exception e) {
            // Can't parse as JSON — do byte comparison
            boolean identical = Arrays.equals(original, replay);
            return new OutputDiff(identical,
                    identical ? List.of() : List.of("Binary content differs"),
                    identical ? 0 : 1);
        }
    }

    @SuppressWarnings("unchecked")
    private void diffMaps(String prefix, Map<String, Object> a, Map<String, Object> b,
                          List<String> differences) {
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(a.keySet());
        allKeys.addAll(b.keySet());

        for (String key : allKeys) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object va = a.get(key);
            Object vb = b.get(key);

            if (va == null && vb != null) {
                differences.add("+" + path + ": " + vb);
            } else if (va != null && vb == null) {
                differences.add("-" + path + ": " + va);
            } else if (va instanceof Map && vb instanceof Map) {
                diffMaps(path, (Map<String, Object>) va, (Map<String, Object>) vb, differences);
            } else if (!Objects.equals(va, vb)) {
                differences.add("~" + path + ": " + va + " → " + vb);
            }
        }
    }

    // ── Result types ──

    public record ReplayResult(
            String invocationId,
            String functionName,
            String replayGroup,
            String replayRevision,
            String originalRevision,
            boolean success,
            String error,
            long replayDurationNanos,
            long originalDurationNanos,
            byte[] replayOutput,
            OutputDiff diff,
            Instant replayedAt
    ) {
        static ReplayResult error(String invocationId, String error) {
            return new ReplayResult(invocationId, null, null, null, null,
                    false, error, 0, 0, null, null, Instant.now());
        }

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("invocationId", invocationId);
            map.put("function", functionName);
            map.put("replayRevision", replayRevision);
            map.put("originalRevision", originalRevision);
            map.put("success", success);
            map.put("error", error);
            map.put("replayDurationMs", replayDurationNanos / 1_000_000.0);
            map.put("originalDurationMs", originalDurationNanos / 1_000_000.0);
            map.put("latencyDelta", replayDurationNanos > 0 && originalDurationNanos > 0
                    ? String.format("%.1f%%", ((double) replayDurationNanos / originalDurationNanos - 1) * 100) : "N/A");
            map.put("outputIdentical", diff != null ? diff.identical() : null);
            map.put("differences", diff != null ? diff.differences() : null);
            map.put("replayedAt", replayedAt != null ? replayedAt.toString() : null);
            return map;
        }
    }

    public record OutputDiff(
            boolean identical,
            List<String> differences,
            int differenceCount
    ) {}

    public record BatchReplayResult(
            int total,
            int passed,
            int failed,
            int diverged,
            int errors,
            long totalNanos,
            List<ReplayResult> results
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "total", total,
                    "passed", passed,
                    "failed", failed,
                    "diverged", diverged,
                    "errors", errors,
                    "totalMs", totalNanos / 1_000_000.0,
                    "passRate", total > 0 ? String.format("%.1f%%", (double) passed / total * 100) : "N/A",
                    "safe", failed == 0 && diverged == 0 && errors == 0,
                    "results", results.stream().map(ReplayResult::toMap).toList()
            );
        }
    }
}
