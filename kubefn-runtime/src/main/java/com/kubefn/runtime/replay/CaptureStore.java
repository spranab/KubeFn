package com.kubefn.runtime.replay;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ring buffer store for invocation captures.
 *
 * <p>Always-on: stores last N invocations (REFERENCE level).
 * Triggered captures (VALUE level) are stored separately with higher retention.
 *
 * <p>Thread-safe. Lock-free reads via ConcurrentLinkedDeque.
 */
public class CaptureStore {
    private static final Logger log = LoggerFactory.getLogger(CaptureStore.class);

    private final int maxReferences;
    private final int maxValues;
    private final ConcurrentLinkedDeque<InvocationCapture> references;
    private final ConcurrentLinkedDeque<InvocationCapture> values;
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong totalValueCaptures = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();

    public CaptureStore() {
        this(10_000, 1_000);
    }

    public CaptureStore(int maxReferences, int maxValues) {
        this.maxReferences = maxReferences;
        this.maxValues = maxValues;
        this.references = new ConcurrentLinkedDeque<>();
        this.values = new ConcurrentLinkedDeque<>();
    }

    /**
     * Store a capture. Automatically routes to reference or value ring.
     */
    public void store(InvocationCapture capture) {
        totalCaptured.incrementAndGet();

        if (capture.level() == InvocationCapture.CaptureLevel.VALUE) {
            values.addFirst(capture);
            totalValueCaptures.incrementAndGet();
            while (values.size() > maxValues) {
                values.removeLast();
                totalDropped.incrementAndGet();
            }
        } else {
            references.addFirst(capture);
            while (references.size() > maxReferences) {
                references.removeLast();
                totalDropped.incrementAndGet();
            }
        }
    }

    /**
     * Get recent captures (most recent first).
     */
    public List<InvocationCapture> recent(int limit) {
        return references.stream().limit(limit).toList();
    }

    /**
     * Get recent value captures (full snapshots).
     */
    public List<InvocationCapture> recentValues(int limit) {
        return values.stream().limit(limit).toList();
    }

    /**
     * Find a specific capture by invocation ID.
     */
    public Optional<InvocationCapture> findById(String invocationId) {
        // Check values first (more likely to be looked up)
        return values.stream()
                .filter(c -> c.invocationId().equals(invocationId))
                .findFirst()
                .or(() -> references.stream()
                        .filter(c -> c.invocationId().equals(invocationId))
                        .findFirst());
    }

    /**
     * Find captures matching a predicate.
     */
    public List<InvocationCapture> find(Predicate<InvocationCapture> predicate, int limit) {
        var results = new ArrayList<InvocationCapture>();
        for (var capture : values) {
            if (predicate.test(capture) && results.size() < limit) {
                results.add(capture);
            }
        }
        for (var capture : references) {
            if (predicate.test(capture) && results.size() < limit) {
                results.add(capture);
            }
        }
        return results;
    }

    /**
     * Find all failed invocations.
     */
    public List<InvocationCapture> failures(int limit) {
        return find(c -> !c.success(), limit);
    }

    /**
     * Find captures for a specific function.
     */
    public List<InvocationCapture> forFunction(String functionName, int limit) {
        return find(c -> c.functionName().equals(functionName), limit);
    }

    /**
     * Status for admin API.
     */
    public Map<String, Object> status() {
        long refBytes = references.stream().mapToLong(InvocationCapture::estimatedBytes).sum();
        long valBytes = values.stream().mapToLong(InvocationCapture::estimatedBytes).sum();

        return Map.of(
                "referenceCaptures", references.size(),
                "valueCaptures", values.size(),
                "maxReferences", maxReferences,
                "maxValues", maxValues,
                "totalCaptured", totalCaptured.get(),
                "totalValueCaptures", totalValueCaptures.get(),
                "totalDropped", totalDropped.get(),
                "estimatedMemoryMB", (refBytes + valBytes) / (1024.0 * 1024.0)
        );
    }
}
