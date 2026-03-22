package com.kubefn.runtime.introspection;

/**
 * Classification of causal events captured during request processing.
 *
 * <p>Each event type maps to a specific phase of KubeFn request lifecycle:
 * <ul>
 *   <li>REQUEST_* — top-level request boundary</li>
 *   <li>FUNCTION_* — individual function invocation boundary</li>
 *   <li>HEAP_* — shared object graph mutations and accesses</li>
 *   <li>PIPELINE_* — multi-function pipeline execution boundary</li>
 *   <li>ERROR — unrecoverable error during processing</li>
 *   <li>CIRCUIT_BREAKER_TRIP — circuit breaker opened for a function</li>
 *   <li>DRAIN_* — group hot-swap drain lifecycle</li>
 * </ul>
 */
public enum EventType {
    REQUEST_START,
    REQUEST_END,
    FUNCTION_START,
    FUNCTION_END,
    HEAP_PUBLISH,
    HEAP_GET_HIT,
    HEAP_GET_MISS,
    HEAP_REMOVE,
    PIPELINE_START,
    PIPELINE_END,
    ERROR,
    CIRCUIT_BREAKER_TRIP,
    DRAIN_START,
    DRAIN_END
}
