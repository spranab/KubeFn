package com.kubefn.runtime.introspection;

import java.util.List;

/**
 * A fully assembled trace for a single KubeFn request.
 *
 * <p>Built on demand by {@link RequestTraceAssembler} from raw
 * {@link CausalEvent}s stored in the {@link CausalEventRing}. Provides
 * a complete causal picture of what happened during a request: which
 * functions ran, how long they took, what heap objects were touched,
 * and whether errors occurred.
 *
 * <p>This is the primary data structure returned by the admin
 * introspection API and consumed by the {@link ReplayEngine}.
 *
 * @param requestId      unique request identifier
 * @param startNanos     monotonic timestamp when request processing began
 * @param endNanos       monotonic timestamp when request processing ended (0 if still in-flight)
 * @param totalTimeMs    total wall-clock time in milliseconds
 * @param entryGroup     the function group that handled this request
 * @param entryFunction  the entry-point function name
 * @param entryRevision  the code revision active for the entry function
 * @param functionCount  total number of function invocations in this trace
 * @param heapPublishes  count of HEAP_PUBLISH events
 * @param heapGets       count of HEAP_GET_HIT + HEAP_GET_MISS events
 * @param heapHits       count of HEAP_GET_HIT events
 * @param errors         count of ERROR events
 * @param steps          ordered list of function invocation steps
 * @param heapMutations  ordered list of heap operations
 */
public record RequestTrace(
        String requestId,
        long startNanos,
        long endNanos,
        double totalTimeMs,
        String entryGroup,
        String entryFunction,
        String entryRevision,
        int functionCount,
        int heapPublishes,
        int heapGets,
        int heapHits,
        int errors,
        List<TraceStep> steps,
        List<HeapMutation> heapMutations
) {

    public RequestTrace {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        steps = steps != null ? List.copyOf(steps) : List.of();
        heapMutations = heapMutations != null ? List.copyOf(heapMutations) : List.of();
    }

    /**
     * Returns true if the request completed (has a non-zero end timestamp).
     */
    public boolean isComplete() {
        return endNanos > 0;
    }

    /**
     * Returns true if any errors occurred during processing.
     */
    public boolean hasErrors() {
        return errors > 0;
    }

    /**
     * Returns the heap hit rate for this request, or 0.0 if no gets occurred.
     */
    public double heapHitRate() {
        return heapGets == 0 ? 0.0 : (double) heapHits / heapGets;
    }
}
