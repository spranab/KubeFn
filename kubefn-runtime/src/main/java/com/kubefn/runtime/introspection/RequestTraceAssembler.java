package com.kubefn.runtime.introspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Assembles a {@link RequestTrace} from raw {@link CausalEvent}s.
 *
 * <p>The assembler is stateless and thread-safe. It processes a list of
 * events (already filtered by requestId) and constructs a complete trace
 * with function invocation steps, heap mutation records, and aggregate
 * statistics.
 *
 * <p>Assembly logic:
 * <ul>
 *   <li>FUNCTION_START/END pairs are matched to build {@link TraceStep}s.
 *       Unmatched START events produce steps with status "TIMEOUT".</li>
 *   <li>HEAP_* events are converted to {@link HeapMutation} records.</li>
 *   <li>REQUEST_START/END events define the trace boundary and entry point.</li>
 *   <li>ERROR events increment the error counter and annotate the
 *       most recent step.</li>
 * </ul>
 */
public class RequestTraceAssembler {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceAssembler.class);

    /**
     * Assembles a complete trace from the given events.
     *
     * <p>Events must all belong to the same requestId. They should be
     * ordered by eventId (ascending), which is the natural order from
     * {@link CausalEventRing#getByRequestId}.
     *
     * @param requestId the request identifier
     * @param events    ordered list of causal events for this request
     * @return assembled trace, or null if events list is empty
     * @throws IllegalArgumentException if requestId is null
     */
    public RequestTrace assemble(String requestId, List<CausalEvent> events) {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        if (events == null || events.isEmpty()) {
            return null;
        }

        long startNanos = 0;
        long endNanos = 0;
        String entryGroup = null;
        String entryFunction = null;
        String entryRevision = null;

        int heapPublishes = 0;
        int heapGets = 0;
        int heapHits = 0;
        int errors = 0;

        List<TraceStep> steps = new ArrayList<>();
        List<HeapMutation> heapMutations = new ArrayList<>();

        // Track open function invocations for START/END matching
        // Key: groupName + ":" + functionName, Value: stack of start events
        Map<String, Deque<CausalEvent>> openFunctions = new HashMap<>();

        for (CausalEvent event : events) {
            switch (event.type()) {
                case REQUEST_START -> {
                    startNanos = event.timestampNanos();
                    entryGroup = event.groupName();
                    entryFunction = event.functionName();
                    entryRevision = event.revisionId();
                }

                case REQUEST_END -> {
                    endNanos = event.timestampNanos();
                }

                case FUNCTION_START -> {
                    String key = functionKey(event.groupName(), event.functionName());
                    openFunctions.computeIfAbsent(key, k -> new ArrayDeque<>()).push(event);
                }

                case FUNCTION_END -> {
                    String key = functionKey(event.groupName(), event.functionName());
                    Deque<CausalEvent> stack = openFunctions.get(key);
                    CausalEvent startEvent = (stack != null && !stack.isEmpty()) ? stack.pop() : null;

                    if (startEvent != null) {
                        long stepDurationNanos = event.timestampNanos() - startEvent.timestampNanos();
                        String status = event.detail() != null ? TraceStep.STATUS_ERROR : TraceStep.STATUS_OK;
                        String error = event.detail();

                        steps.add(new TraceStep(
                                event.functionName(),
                                event.groupName(),
                                event.revisionId(),
                                startEvent.timestampNanos(),
                                event.timestampNanos(),
                                stepDurationNanos / 1_000_000.0,
                                status,
                                error
                        ));
                    } else {
                        // Unmatched END — log but still record the step
                        log.warn("Unmatched FUNCTION_END for {}.{} in request {}",
                                event.groupName(), event.functionName(), requestId);
                        steps.add(new TraceStep(
                                event.functionName(),
                                event.groupName(),
                                event.revisionId(),
                                0,
                                event.timestampNanos(),
                                event.durationNanos() / 1_000_000.0,
                                event.detail() != null ? TraceStep.STATUS_ERROR : TraceStep.STATUS_OK,
                                event.detail()
                        ));
                    }
                }

                case HEAP_PUBLISH -> {
                    heapPublishes++;
                    heapMutations.add(toHeapMutation(event));
                }

                case HEAP_GET_HIT -> {
                    heapGets++;
                    heapHits++;
                    heapMutations.add(toHeapMutation(event));
                }

                case HEAP_GET_MISS -> {
                    heapGets++;
                    heapMutations.add(toHeapMutation(event));
                }

                case HEAP_REMOVE -> {
                    heapMutations.add(toHeapMutation(event));
                }

                case ERROR -> {
                    errors++;
                }

                case CIRCUIT_BREAKER_TRIP -> {
                    errors++;
                }

                // PIPELINE_START, PIPELINE_END, DRAIN_START, DRAIN_END:
                // recorded but not currently modeled as separate trace elements
                default -> { /* no-op for informational events */ }
            }
        }

        // Handle unmatched FUNCTION_START events (functions that never completed)
        for (Map.Entry<String, Deque<CausalEvent>> entry : openFunctions.entrySet()) {
            for (CausalEvent orphan : entry.getValue()) {
                steps.add(new TraceStep(
                        orphan.functionName(),
                        orphan.groupName(),
                        orphan.revisionId(),
                        orphan.timestampNanos(),
                        0,
                        0.0,
                        TraceStep.STATUS_TIMEOUT,
                        "Function did not complete — no matching FUNCTION_END"
                ));
            }
        }

        // Sort steps by start time
        steps.sort(Comparator.comparingLong(TraceStep::startNanos));

        double totalTimeMs = (endNanos > 0 && startNanos > 0)
                ? (endNanos - startNanos) / 1_000_000.0
                : 0.0;

        return new RequestTrace(
                requestId,
                startNanos,
                endNanos,
                totalTimeMs,
                entryGroup != null ? entryGroup : "unknown",
                entryFunction != null ? entryFunction : "unknown",
                entryRevision != null ? entryRevision : "unknown",
                steps.size(),
                heapPublishes,
                heapGets,
                heapHits,
                errors,
                steps,
                heapMutations
        );
    }

    private static HeapMutation toHeapMutation(CausalEvent event) {
        return new HeapMutation(
                event.type(),
                event.heapKey() != null ? event.heapKey() : "unknown",
                event.heapObjectType(),
                event.heapVersion(),
                event.functionName(),
                event.groupName(),
                event.timestampNanos()
        );
    }

    private static String functionKey(String group, String function) {
        return (group != null ? group : "") + ":" + (function != null ? function : "");
    }
}
