package com.kubefn.runtime.replay;

import java.io.*;
import java.time.Instant;
import java.util.*;

/**
 * Captured snapshot of a function invocation for replay.
 *
 * <p>Two capture tiers:
 * <ul>
 *   <li><b>REFERENCE</b> (always-on): causal refs only — heap keys read/written
 *       with content hashes. Cheap. Enables causal tracing.</li>
 *   <li><b>VALUE</b> (triggered): full binary snapshot of input/output objects.
 *       Triggered on error, anomaly, watchlist match, or policy.</li>
 * </ul>
 *
 * <p>Storage: ring buffer in memory (last N invocations), spill to disk on trigger.
 */
public class InvocationCapture implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // ── Identity ──
    private final String invocationId;
    private final String requestId;
    private final String functionName;
    private final String groupName;
    private final String revisionId;
    private final Instant timestamp;

    // ── Tier 0: Causal References (always captured) ──
    private final List<HeapRef> heapReads;
    private final List<HeapRef> heapWrites;
    private final long durationNanos;
    private final boolean success;
    private final String errorMessage;
    private final String httpMethod;
    private final String httpPath;
    private final int httpStatus;

    // ── Tier 1: Value Snapshots (captured on trigger) ──
    private final CaptureLevel level;
    private byte[] inputSnapshot;   // binary serialized input
    private byte[] outputSnapshot;  // binary serialized output
    private String inputFormat;     // "kryo", "java", "json"
    private String outputFormat;

    public InvocationCapture(
            String invocationId, String requestId,
            String functionName, String groupName, String revisionId,
            String httpMethod, String httpPath) {
        this.invocationId = invocationId;
        this.requestId = requestId;
        this.functionName = functionName;
        this.groupName = groupName;
        this.revisionId = revisionId;
        this.timestamp = Instant.now();
        this.httpMethod = httpMethod;
        this.httpPath = httpPath;
        this.heapReads = new ArrayList<>();
        this.heapWrites = new ArrayList<>();
        this.durationNanos = 0;
        this.success = true;
        this.errorMessage = null;
        this.httpStatus = 200;
        this.level = CaptureLevel.REFERENCE;
    }

    private InvocationCapture(Builder b) {
        this.invocationId = b.invocationId;
        this.requestId = b.requestId;
        this.functionName = b.functionName;
        this.groupName = b.groupName;
        this.revisionId = b.revisionId;
        this.timestamp = b.timestamp;
        this.httpMethod = b.httpMethod;
        this.httpPath = b.httpPath;
        this.heapReads = b.heapReads;
        this.heapWrites = b.heapWrites;
        this.durationNanos = b.durationNanos;
        this.success = b.success;
        this.errorMessage = b.errorMessage;
        this.httpStatus = b.httpStatus;
        this.level = b.level;
        this.inputSnapshot = b.inputSnapshot;
        this.outputSnapshot = b.outputSnapshot;
        this.inputFormat = b.inputFormat;
        this.outputFormat = b.outputFormat;
    }

    // ── Heap Reference ──

    /**
     * A reference to a heap key read or written during invocation.
     * Always captured (Tier 0). Includes content hash for versioning.
     */
    public record HeapRef(
            String key,
            String typeClass,
            long contentHash,
            int sizeBytes,
            Instant capturedAt
    ) implements Serializable {}

    // ── Capture Level ──

    public enum CaptureLevel {
        /** Only causal references — heap keys + hashes */
        REFERENCE,
        /** Full binary snapshot of input/output */
        VALUE
    }

    // ── Builder ──

    public static Builder builder(String invocationId, String functionName, String groupName) {
        return new Builder(invocationId, functionName, groupName);
    }

    public static class Builder {
        private String invocationId;
        private String requestId;
        private String functionName;
        private String groupName;
        private String revisionId;
        private Instant timestamp = Instant.now();
        private String httpMethod;
        private String httpPath;
        private final List<HeapRef> heapReads = new ArrayList<>();
        private final List<HeapRef> heapWrites = new ArrayList<>();
        private long durationNanos;
        private boolean success = true;
        private String errorMessage;
        private int httpStatus = 200;
        private CaptureLevel level = CaptureLevel.REFERENCE;
        private byte[] inputSnapshot;
        private byte[] outputSnapshot;
        private String inputFormat;
        private String outputFormat;

        Builder(String invocationId, String functionName, String groupName) {
            this.invocationId = invocationId;
            this.functionName = functionName;
            this.groupName = groupName;
        }

        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder revisionId(String v) { this.revisionId = v; return this; }
        public Builder httpMethod(String v) { this.httpMethod = v; return this; }
        public Builder httpPath(String v) { this.httpPath = v; return this; }
        public Builder durationNanos(long v) { this.durationNanos = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder error(String v) { this.success = false; this.errorMessage = v; return this; }
        public Builder httpStatus(int v) { this.httpStatus = v; return this; }

        public Builder heapRead(String key, String typeClass, long contentHash, int sizeBytes) {
            heapReads.add(new HeapRef(key, typeClass, contentHash, sizeBytes, Instant.now()));
            return this;
        }

        public Builder heapWrite(String key, String typeClass, long contentHash, int sizeBytes) {
            heapWrites.add(new HeapRef(key, typeClass, contentHash, sizeBytes, Instant.now()));
            return this;
        }

        public Builder withValueCapture(byte[] input, String inputFmt, byte[] output, String outputFmt) {
            this.level = CaptureLevel.VALUE;
            this.inputSnapshot = input;
            this.inputFormat = inputFmt;
            this.outputSnapshot = output;
            this.outputFormat = outputFmt;
            return this;
        }

        public InvocationCapture build() {
            return new InvocationCapture(this);
        }
    }

    // ── Accessors ──

    public String invocationId() { return invocationId; }
    public String requestId() { return requestId; }
    public String functionName() { return functionName; }
    public String groupName() { return groupName; }
    public String revisionId() { return revisionId; }
    public Instant timestamp() { return timestamp; }
    public List<HeapRef> heapReads() { return Collections.unmodifiableList(heapReads); }
    public List<HeapRef> heapWrites() { return Collections.unmodifiableList(heapWrites); }
    public long durationNanos() { return durationNanos; }
    public boolean success() { return success; }
    public String errorMessage() { return errorMessage; }
    public CaptureLevel level() { return level; }
    public byte[] inputSnapshot() { return inputSnapshot; }
    public byte[] outputSnapshot() { return outputSnapshot; }

    /**
     * Estimated memory footprint of this capture.
     */
    public int estimatedBytes() {
        int base = 256; // fixed fields
        base += heapReads.size() * 128;
        base += heapWrites.size() * 128;
        if (inputSnapshot != null) base += inputSnapshot.length;
        if (outputSnapshot != null) base += outputSnapshot.length;
        return base;
    }

    /**
     * Convert to JSON-compatible map for admin API.
     */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("invocationId", invocationId);
        map.put("requestId", requestId);
        map.put("function", functionName);
        map.put("group", groupName);
        map.put("revision", revisionId);
        map.put("timestamp", timestamp.toString());
        map.put("method", httpMethod);
        map.put("path", httpPath);
        map.put("status", httpStatus);
        map.put("durationMs", durationNanos / 1_000_000.0);
        map.put("success", success);
        map.put("error", errorMessage);
        map.put("captureLevel", level.name());
        map.put("heapReads", heapReads.stream().map(r -> Map.of(
                "key", r.key(), "type", r.typeClass(),
                "hash", Long.toHexString(r.contentHash()),
                "bytes", r.sizeBytes()
        )).toList());
        map.put("heapWrites", heapWrites.stream().map(r -> Map.of(
                "key", r.key(), "type", r.typeClass(),
                "hash", Long.toHexString(r.contentHash()),
                "bytes", r.sizeBytes()
        )).toList());
        map.put("hasInputSnapshot", inputSnapshot != null);
        map.put("hasOutputSnapshot", outputSnapshot != null);
        map.put("estimatedBytes", estimatedBytes());
        return map;
    }
}
