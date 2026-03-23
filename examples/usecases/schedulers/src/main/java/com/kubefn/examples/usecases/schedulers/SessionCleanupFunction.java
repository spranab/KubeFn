package com.kubefn.examples.usecases.schedulers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SessionCleanupFunction — Replaces a Kubernetes CronJob container.
 *
 * BEFORE (traditional K8s):
 *   - A separate CronJob deployment with its own container image
 *   - Needs access to Redis/Memcached to clean sessions
 *   - Separate resource allocation (CPU/memory requests and limits)
 *   - Own service account, RBAC, network policies
 *   - Cold start every 15 minutes — pulls image, starts JVM, connects to store
 *
 * AFTER (KubeFn):
 *   - A single function in the organism — no container, no cold start
 *   - Sessions live on HeapExchange — cleanup is just heap.remove()
 *   - Zero-copy access to session data, no network hop to Redis
 *   - Also exposes an HTTP endpoint for manual/on-demand cleanup
 */
@FnSchedule(cron = "0 0/15 * * *", timeoutMs = 30000, skipIfRunning = true)
@FnRoute(path = "/admin/sessions/cleanup", methods = {"POST"})
@FnGroup("platform-ops")
public class SessionCleanupFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(SessionCleanupFunction.class.getName());
    private static final String SESSION_PREFIX = "session:";
    private static final long SESSION_TTL_MS = 30 * 60 * 1000; // 30 minutes

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long now = Instant.now().toEpochMilli();
        List<String> expiredKeys = new ArrayList<>();
        int scanned = 0;

        // Iterate all heap keys, find session entries that have expired
        for (String key : ctx.heap().keys()) {
            if (!key.startsWith(SESSION_PREFIX)) {
                continue;
            }
            scanned++;

            // Each session stores its last-access timestamp
            ctx.heap().get(key, SessionEntry.class).ifPresent(session -> {
                long age = now - session.lastAccessedAt();
                if (age > SESSION_TTL_MS) {
                    expiredKeys.add(key);
                }
            });
        }

        // Remove all expired sessions from the heap
        for (String key : expiredKeys) {
            ctx.heap().remove(key);
        }

        LOG.info(String.format("Session cleanup: scanned=%d, expired=%d, remaining=%d",
                scanned, expiredKeys.size(), scanned - expiredKeys.size()));

        // Publish cleanup stats so monitoring functions can read them
        var stats = new CleanupStats(now, scanned, expiredKeys.size());
        ctx.heap().publish("ops:last-cleanup", stats);

        return KubeFnResponse.ok(Map.of(
                "scanned", scanned,
                "cleaned", expiredKeys.size(),
                "timestamp", now
        ));
    }

    /** Heap-stored session entry — shared across all functions in the organism. */
    public record SessionEntry(String userId, long createdAt, long lastAccessedAt, Map<String, Object> data) {}

    /** Published after each cleanup run for observability. */
    public record CleanupStats(long timestamp, int scanned, int removed) {}
}
