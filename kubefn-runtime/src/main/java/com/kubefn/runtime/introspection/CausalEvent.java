package com.kubefn.runtime.introspection;

/**
 * Immutable record capturing a single causal event on the KubeFn hot path.
 *
 * <p>Events are produced by {@link CausalCaptureEngine} and stored in the
 * {@link CausalEventRing} lock-free ring buffer. They form the raw material
 * from which {@link RequestTrace} objects are assembled on demand.
 *
 * <p>Design notes:
 * <ul>
 *   <li>All timestamps use {@link System#nanoTime()} for monotonic precision</li>
 *   <li>Heap-related fields are null/0 for non-heap events</li>
 *   <li>Duration fields are 0 for instantaneous events (START, PUBLISH, etc.)</li>
 *   <li>The {@code detail} field carries optional context (error messages, etc.)</li>
 * </ul>
 *
 * @param eventId        globally unique, monotonically increasing event identifier
 * @param timestampNanos monotonic timestamp from {@link System#nanoTime()}
 * @param requestId      the request that caused this event
 * @param type           classification of this event
 * @param groupName      function group that produced this event
 * @param functionName   specific function within the group (may be null for group-level events)
 * @param revisionId     revision of the function at capture time
 * @param heapKey        heap object key (null for non-heap events)
 * @param heapObjectType heap object type name (null for non-heap events)
 * @param heapVersion    heap object version (0 for non-heap events)
 * @param durationNanos  duration in nanoseconds (0 for non-duration events)
 * @param detail         optional human-readable detail (error message, etc.)
 */
public record CausalEvent(
        long eventId,
        long timestampNanos,
        String requestId,
        EventType type,
        String groupName,
        String functionName,
        String revisionId,
        String heapKey,
        String heapObjectType,
        long heapVersion,
        long durationNanos,
        String detail
) {

    /**
     * Compact constructor with null-safety on required fields.
     */
    public CausalEvent {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
    }

    /**
     * Returns the duration of this event in milliseconds, or 0.0 if not a duration event.
     */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /**
     * Returns true if this event is related to heap operations.
     */
    public boolean isHeapEvent() {
        return type == EventType.HEAP_PUBLISH
                || type == EventType.HEAP_GET_HIT
                || type == EventType.HEAP_GET_MISS
                || type == EventType.HEAP_REMOVE;
    }

    /**
     * Returns true if this is an error or circuit breaker event.
     */
    public boolean isFailureEvent() {
        return type == EventType.ERROR || type == EventType.CIRCUIT_BREAKER_TRIP;
    }
}
