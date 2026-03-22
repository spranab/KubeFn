package io.kubefn.runtime.heap;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Mutation audit log for the HeapExchange. Records every publish, remove,
 * and access with full attribution metadata.
 *
 * <p>This is the precursor to deterministic replay. Every mutation is tracked
 * with: key, type, publisher function, revision, timestamp, version.
 *
 * <p>Queryable via admin API for debugging and causal state introspection.
 */
public class HeapAuditLog {

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();

    public void recordPublish(String key, String type, String group, String function,
                              String revision, long version) {
        append(new AuditEntry(
                AuditAction.PUBLISH, key, type, group, function, revision,
                version, Instant.now(), null
        ));
    }

    public void recordRemove(String key, String group, String function, String revision) {
        append(new AuditEntry(
                AuditAction.REMOVE, key, null, group, function, revision,
                -1, Instant.now(), null
        ));
    }

    public void recordAccess(String key, String type, String group, String function,
                             String revision, boolean hit) {
        append(new AuditEntry(
                hit ? AuditAction.GET_HIT : AuditAction.GET_MISS,
                key, type, group, function, revision,
                -1, Instant.now(), null
        ));
    }

    /**
     * Get recent entries, optionally filtered by key.
     */
    public List<AuditEntry> recent(int limit, String filterKey) {
        return entries.stream()
                .filter(e -> filterKey == null || filterKey.equals(e.key()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get all mutations (publishes + removes) for a key, ordered by time.
     */
    public List<AuditEntry> mutationsForKey(String key) {
        return entries.stream()
                .filter(e -> key.equals(e.key()))
                .filter(e -> e.action() == AuditAction.PUBLISH || e.action() == AuditAction.REMOVE)
                .collect(Collectors.toList());
    }

    /**
     * Get all mutations by a specific function revision.
     */
    public List<AuditEntry> mutationsByRevision(String revision) {
        return entries.stream()
                .filter(e -> revision.equals(e.revision()))
                .filter(e -> e.action() == AuditAction.PUBLISH || e.action() == AuditAction.REMOVE)
                .collect(Collectors.toList());
    }

    public int size() {
        return entries.size();
    }

    private void append(AuditEntry entry) {
        entries.addFirst(entry);
        // Evict old entries
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public enum AuditAction {
        PUBLISH, REMOVE, GET_HIT, GET_MISS
    }

    public record AuditEntry(
            AuditAction action,
            String key,
            String type,
            String group,
            String function,
            String revision,
            long version,
            Instant timestamp,
            String requestId
    ) {}
}
