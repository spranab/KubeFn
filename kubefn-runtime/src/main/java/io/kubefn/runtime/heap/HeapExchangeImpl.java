package io.kubefn.runtime.heap;

import io.kubefn.api.HeapCapsule;
import io.kubefn.api.HeapExchange;
import io.kubefn.runtime.metrics.KubeFnMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Shared Object Graph Fabric — KubeFn's revolutionary zero-copy data plane.
 *
 * <p>Now integrated with:
 * - {@link HeapGuard}: capacity limits, leak detection, stale eviction
 * - {@link HeapAuditLog}: full mutation tracking for causal introspection
 * - {@link KubeFnMetrics}: publish/get counters for observability
 */
public class HeapExchangeImpl implements HeapExchange {

    private static final Logger log = LoggerFactory.getLogger(HeapExchangeImpl.class);

    private final ConcurrentHashMap<String, HeapCapsule<?>> store = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    // Integrated hardening components
    private final HeapGuard guard;
    private final HeapAuditLog auditLog;

    // Counters
    private final AtomicLong publishCount = new AtomicLong(0);
    private final AtomicLong getCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    // Thread-local context for publisher attribution
    private static final ThreadLocal<String> currentGroup = new ThreadLocal<>();
    private static final ThreadLocal<String> currentFunction = new ThreadLocal<>();

    public HeapExchangeImpl() {
        this(HeapGuard.defaults(), new HeapAuditLog());
    }

    public HeapExchangeImpl(HeapGuard guard, HeapAuditLog auditLog) {
        this.guard = guard;
        this.auditLog = auditLog;
    }

    public static void setCurrentContext(String group, String function) {
        currentGroup.set(group);
        currentFunction.set(function);
    }

    public static void clearCurrentContext() {
        currentGroup.remove();
        currentFunction.remove();
    }

    @Override
    public <T> HeapCapsule<T> publish(String key, T value, Class<T> type) {
        String group = currentGroup.get() != null ? currentGroup.get() : "unknown";
        String function = currentFunction.get() != null ? currentFunction.get() : "unknown";

        // HeapGuard: check capacity before publishing
        String guardError = guard.checkPublish(key, value, store.size());
        if (guardError != null) {
            log.error("HeapGuard BLOCKED publish '{}': {}", key, guardError);
            throw new IllegalStateException("HeapExchange publish blocked: " + guardError);
        }

        long version = versionCounter.incrementAndGet();
        HeapCapsule<T> capsule = new HeapCapsule<>(
                key, value, type, version, group, function, Instant.now()
        );

        store.put(key, capsule);
        publishCount.incrementAndGet();

        // HeapGuard: track for size/staleness
        guard.recordPublish(key, value);

        // AuditLog: record mutation
        auditLog.recordPublish(key, type.getSimpleName(), group, function,
                currentRevision(), version);

        // Metrics
        KubeFnMetrics.instance().recordHeapPublish();

        log.debug("HeapExchange: published '{}' (type={}, v={}, by={}.{})",
                key, type.getSimpleName(), version, group, function);

        return capsule;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        getCount.incrementAndGet();
        HeapCapsule<?> capsule = store.get(key);
        String group = currentGroup.get() != null ? currentGroup.get() : "unknown";
        String function = currentFunction.get() != null ? currentFunction.get() : "unknown";

        if (capsule == null) {
            missCount.incrementAndGet();
            KubeFnMetrics.instance().recordHeapGet(false);
            auditLog.recordAccess(key, type.getSimpleName(), group, function,
                    currentRevision(), false);
            return Optional.empty();
        }

        if (!type.isAssignableFrom(capsule.type())) {
            log.warn("HeapExchange: type mismatch for '{}'. Expected={}, actual={}",
                    key, type.getSimpleName(), capsule.type().getSimpleName());
            missCount.incrementAndGet();
            KubeFnMetrics.instance().recordHeapGet(false);
            return Optional.empty();
        }

        hitCount.incrementAndGet();
        KubeFnMetrics.instance().recordHeapGet(true);

        // HeapGuard: track access for staleness
        guard.recordAccess(key);

        // AuditLog: record access
        auditLog.recordAccess(key, type.getSimpleName(), group, function,
                currentRevision(), true);

        // Zero copy: return the SAME object reference
        return Optional.of((T) capsule.value());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<HeapCapsule<T>> getCapsule(String key, Class<T> type) {
        getCount.incrementAndGet();
        HeapCapsule<?> capsule = store.get(key);

        if (capsule == null || !type.isAssignableFrom(capsule.type())) {
            missCount.incrementAndGet();
            KubeFnMetrics.instance().recordHeapGet(false);
            return Optional.empty();
        }

        hitCount.incrementAndGet();
        KubeFnMetrics.instance().recordHeapGet(true);
        guard.recordAccess(key);
        return Optional.of((HeapCapsule<T>) capsule);
    }

    @Override
    public boolean remove(String key) {
        HeapCapsule<?> removed = store.remove(key);
        if (removed != null) {
            String group = currentGroup.get() != null ? currentGroup.get() : "unknown";
            String function = currentFunction.get() != null ? currentFunction.get() : "unknown";
            guard.recordRemove(key);
            auditLog.recordRemove(key, group, function, currentRevision());
            log.debug("HeapExchange: removed '{}' (was v{})", key, removed.version());
            return true;
        }
        return false;
    }

    /**
     * Evict stale objects (not accessed within guard threshold).
     * Called periodically by the runtime.
     */
    public int evictStale() {
        Set<String> stale = guard.findStaleKeys();
        int evicted = 0;
        for (String key : stale) {
            if (remove(key)) {
                evicted++;
                log.info("HeapExchange: evicted stale object '{}'", key);
            }
        }
        return evicted;
    }

    @Override
    public Set<String> keys() { return Set.copyOf(store.keySet()); }

    @Override
    public boolean contains(String key) { return store.containsKey(key); }

    public HeapMetrics metrics() {
        return new HeapMetrics(
                store.size(), publishCount.get(), getCount.get(),
                hitCount.get(), missCount.get()
        );
    }

    public HeapGuard guard() { return guard; }
    public HeapAuditLog auditLog() { return auditLog; }

    private static String currentRevision() {
        var ctx = io.kubefn.runtime.lifecycle.RevisionContext.current();
        return ctx != null ? ctx.requestId() : "unknown";
    }

    public record HeapMetrics(
            int objectCount, long publishCount, long getCount,
            long hitCount, long missCount
    ) {
        public double hitRate() {
            return getCount == 0 ? 0.0 : (double) hitCount / getCount;
        }
    }
}
