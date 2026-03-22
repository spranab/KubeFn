package com.kubefn.runtime.introspection;

/**
 * Records a single mutation or access to the shared HeapExchange
 * within the context of a traced request.
 *
 * <p>Captures the full causal context: which function in which group
 * touched which heap key, at what version, and when. This enables
 * post-hoc analysis of data flow between functions sharing the
 * object graph.
 *
 * @param type           the heap operation type (HEAP_PUBLISH, HEAP_GET_HIT, HEAP_GET_MISS, HEAP_REMOVE)
 * @param key            the heap object key
 * @param objectType     the Java type name of the heap object
 * @param version        the heap object version at time of operation
 * @param byFunction     the function that performed this operation
 * @param byGroup        the function group that performed this operation
 * @param timestampNanos monotonic timestamp of the operation
 */
public record HeapMutation(
        EventType type,
        String key,
        String objectType,
        long version,
        String byFunction,
        String byGroup,
        long timestampNanos
) {

    public HeapMutation {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    /**
     * Returns true if this mutation wrote to the heap (publish or remove).
     */
    public boolean isWrite() {
        return type == EventType.HEAP_PUBLISH || type == EventType.HEAP_REMOVE;
    }

    /**
     * Returns true if this mutation was a read (hit or miss).
     */
    public boolean isRead() {
        return type == EventType.HEAP_GET_HIT || type == EventType.HEAP_GET_MISS;
    }
}
