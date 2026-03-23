package com.kubefn.examples.usecases.schedulers;

import com.kubefn.api.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

/**
 * DataSyncFunction — Replaces a daily CronJob container that syncs reference data.
 *
 * BEFORE (traditional K8s):
 *   - A CronJob Deployment with its own image, running once a day
 *   - Pulls data from an external API/DB, transforms it, writes to shared DB or cache
 *   - Every consuming microservice then re-reads the same data from the shared DB
 *   - Cold start overhead: pull image + JVM boot + DB connect = 30-60 seconds before work
 *
 * AFTER (KubeFn):
 *   - One function, already warm in the organism, runs daily via @FnSchedule
 *   - Fetches external data, diffs it against what's currently on the heap
 *   - Updates only changed entries — minimal heap writes
 *   - All consumer functions instantly see the new data, zero-copy, zero-latency
 */
@FnSchedule(cron = "0 0 * * *", timeoutMs = 120000, skipIfRunning = true)
@FnRoute(path = "/admin/data-sync", methods = {"POST"})
@FnGroup("platform-ops")
public class DataSyncFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(DataSyncFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long start = System.currentTimeMillis();
        int added = 0, updated = 0, unchanged = 0, removed = 0;

        // 1. Fetch latest data from external source
        Map<String, ExchangeRate> freshRates = fetchExchangeRates();

        // 2. Load current data from heap for comparison
        @SuppressWarnings("unchecked")
        Map<String, ExchangeRate> currentRates = ctx.heap()
                .get("ref:exchange-rates", Map.class)
                .orElse(Collections.emptyMap());

        // 3. Diff: find additions, updates, and removals
        Map<String, ExchangeRate> merged = new HashMap<>(currentRates);

        for (var entry : freshRates.entrySet()) {
            String key = entry.getKey();
            ExchangeRate freshRate = entry.getValue();
            ExchangeRate currentRate = currentRates.get(key);

            if (currentRate == null) {
                merged.put(key, freshRate);
                added++;
            } else if (!currentRate.equals(freshRate)) {
                merged.put(key, freshRate);
                updated++;
            } else {
                unchanged++;
            }
        }

        // Remove rates that no longer exist in the source
        for (String key : currentRates.keySet()) {
            if (!freshRates.containsKey(key)) {
                merged.remove(key);
                removed++;
            }
        }

        // 4. Publish updated data to heap only if there were changes
        if (added > 0 || updated > 0 || removed > 0) {
            ctx.heap().publish("ref:exchange-rates", merged);
        }

        long elapsed = System.currentTimeMillis() - start;

        // 5. Publish sync status for observability
        var status = new SyncStatus(Instant.now().toEpochMilli(),
                added, updated, unchanged, removed, elapsed, LocalDate.now().toString());
        ctx.heap().publish("ops:data-sync-status", status);

        LOG.info(String.format("Data sync: added=%d, updated=%d, unchanged=%d, removed=%d (%dms)",
                added, updated, unchanged, removed, elapsed));

        return KubeFnResponse.ok(Map.of(
                "added", added, "updated", updated,
                "unchanged", unchanged, "removed", removed,
                "elapsedMs", elapsed
        ));
    }

    /** Simulated external data fetch — in production, this hits an API or database. */
    private Map<String, ExchangeRate> fetchExchangeRates() {
        return Map.of(
                "USD/EUR", new ExchangeRate("USD", "EUR", 0.92, Instant.now().toEpochMilli()),
                "USD/GBP", new ExchangeRate("USD", "GBP", 0.79, Instant.now().toEpochMilli()),
                "USD/JPY", new ExchangeRate("USD", "JPY", 149.50, Instant.now().toEpochMilli()),
                "USD/CAD", new ExchangeRate("USD", "CAD", 1.36, Instant.now().toEpochMilli()),
                "USD/AUD", new ExchangeRate("USD", "AUD", 1.53, Instant.now().toEpochMilli())
        );
    }

    public record ExchangeRate(String from, String to, double rate, long fetchedAt) {}
    public record SyncStatus(long timestamp, int added, int updated, int unchanged,
                             int removed, long elapsedMs, String syncDate) {}
}
