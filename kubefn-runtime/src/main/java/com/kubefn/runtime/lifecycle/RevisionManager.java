package com.kubefn.runtime.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages multiple live revisions of function groups.
 * Enables canary deployments and blue-green switching within a single JVM.
 *
 * <p>Each group can have multiple active revisions simultaneously.
 * Traffic is split according to configured weights.
 *
 * <p>This is the "Live Revision Coexistence" primitive — run v1 and v2
 * in the same JVM, feed them identical inputs (zero-copy), diff outputs.
 */
public class RevisionManager {

    private static final Logger log = LoggerFactory.getLogger(RevisionManager.class);

    /**
     * Active revisions per group, ordered by deployment time (newest first).
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<RevisionEntry>> activeRevisions =
            new ConcurrentHashMap<>();

    /**
     * Traffic weights per revision (revision -> weight 0-100).
     * Default: latest revision gets 100%.
     */
    private final ConcurrentHashMap<String, Integer> revisionWeights = new ConcurrentHashMap<>();

    /**
     * Register a new revision for a group.
     */
    public void registerRevision(String groupName, String revisionId, RevisionState state) {
        activeRevisions.computeIfAbsent(groupName, k -> new CopyOnWriteArrayList<>())
                .add(0, new RevisionEntry(revisionId, state, System.currentTimeMillis()));

        // Default: new revision gets 100%, old ones get 0%
        var revisions = activeRevisions.get(groupName);
        for (int i = 0; i < revisions.size(); i++) {
            revisionWeights.put(revisions.get(i).revisionId(), i == 0 ? 100 : 0);
        }

        log.info("Registered revision '{}' for group '{}' (state={})",
                revisionId, groupName, state);
    }

    /**
     * Update the state of a revision.
     */
    public void updateState(String groupName, String revisionId, RevisionState newState) {
        var revisions = activeRevisions.get(groupName);
        if (revisions != null) {
            for (int i = 0; i < revisions.size(); i++) {
                if (revisions.get(i).revisionId().equals(revisionId)) {
                    revisions.set(i, new RevisionEntry(revisionId, newState,
                            revisions.get(i).deployedAt()));
                    log.info("Revision '{}' state changed to {}", revisionId, newState);
                    break;
                }
            }
        }
    }

    /**
     * Remove a revision (after drain + unload).
     */
    public void removeRevision(String groupName, String revisionId) {
        var revisions = activeRevisions.get(groupName);
        if (revisions != null) {
            revisions.removeIf(r -> r.revisionId().equals(revisionId));
            revisionWeights.remove(revisionId);
            log.info("Removed revision '{}' from group '{}'", revisionId, groupName);
        }
    }

    /**
     * Set traffic weight for a revision (0-100).
     * Enables canary: setWeight("rev-new", 10) sends 10% to new version.
     */
    public void setWeight(String revisionId, int weight) {
        if (weight < 0 || weight > 100) {
            throw new IllegalArgumentException("Weight must be 0-100, got " + weight);
        }
        revisionWeights.put(revisionId, weight);
        log.info("Revision '{}' weight set to {}%", revisionId, weight);
    }

    /**
     * Select which revision should handle a request, based on weights.
     * Uses weighted random selection.
     */
    public String selectRevision(String groupName) {
        var revisions = activeRevisions.get(groupName);
        if (revisions == null || revisions.isEmpty()) return null;
        if (revisions.size() == 1) return revisions.get(0).revisionId();

        // Weighted selection
        int totalWeight = 0;
        for (var rev : revisions) {
            totalWeight += revisionWeights.getOrDefault(rev.revisionId(), 0);
        }

        if (totalWeight == 0) {
            // Fallback: use latest active revision
            return revisions.stream()
                    .filter(r -> r.state() == RevisionState.ACTIVE)
                    .findFirst()
                    .map(RevisionEntry::revisionId)
                    .orElse(revisions.get(0).revisionId());
        }

        int random = (int) (Math.random() * totalWeight);
        int cumulative = 0;
        for (var rev : revisions) {
            cumulative += revisionWeights.getOrDefault(rev.revisionId(), 0);
            if (random < cumulative) {
                return rev.revisionId();
            }
        }

        return revisions.get(0).revisionId();
    }

    /**
     * Get all active revisions for a group.
     */
    public List<RevisionEntry> getRevisions(String groupName) {
        var revisions = activeRevisions.get(groupName);
        return revisions != null ? Collections.unmodifiableList(revisions) : List.of();
    }

    /**
     * Get the latest active revision for a group.
     */
    public String getLatestRevision(String groupName) {
        var revisions = activeRevisions.get(groupName);
        if (revisions == null || revisions.isEmpty()) return null;
        return revisions.get(0).revisionId();
    }

    /**
     * Get weight for a revision.
     */
    public int getWeight(String revisionId) {
        return revisionWeights.getOrDefault(revisionId, 0);
    }

    /**
     * Get all revision info for admin API.
     */
    public Map<String, List<RevisionEntry>> allRevisions() {
        return Collections.unmodifiableMap(activeRevisions);
    }

    /**
     * A live revision entry.
     */
    public record RevisionEntry(
            String revisionId,
            RevisionState state,
            long deployedAt
    ) {}
}
