package io.kubefn.runtime.lifecycle;

import java.time.Instant;
import java.util.Map;

/**
 * Request-scoped revision context. Ensures a request that enters
 * on revision set R1 finishes on R1 — even if a hot-swap happens mid-flight.
 *
 * <p>This is the core safety primitive for Memory-Continuous Architecture.
 * Without revision pinning, in-flight requests could cross code versions
 * and produce nondeterministic behavior.
 */
public record RevisionContext(
        String requestId,
        Map<String, String> groupRevisions,  // group → revisionId
        Instant createdAt
) {

    /**
     * Thread-local storage for the current request's revision context.
     * Set at request entry, read by all functions in the pipeline.
     */
    private static final ThreadLocal<RevisionContext> CURRENT = new ThreadLocal<>();

    public static void setCurrent(RevisionContext ctx) {
        CURRENT.set(ctx);
    }

    public static RevisionContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Get the pinned revision for a group.
     */
    public String revisionFor(String groupName) {
        return groupRevisions.getOrDefault(groupName, "unknown");
    }
}
