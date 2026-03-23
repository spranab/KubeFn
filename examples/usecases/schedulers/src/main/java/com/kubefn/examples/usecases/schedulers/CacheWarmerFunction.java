package com.kubefn.examples.usecases.schedulers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * CacheWarmerFunction — Replaces a dedicated cache-warming container/init-container.
 *
 * BEFORE (traditional K8s):
 *   - An init container that preloads Redis/Memcached before the app starts
 *   - A separate CronJob container to refresh the cache periodically
 *   - Each microservice has its own copy of reference data in its own Redis
 *   - N services x M GB cache = N*M GB total memory across the cluster
 *
 * AFTER (KubeFn):
 *   - One function loads reference data onto HeapExchange at startup + every 5 min
 *   - ALL functions in the organism read this data zero-copy from shared heap
 *   - One copy of the data serves every function — massive memory savings
 *   - runOnStart=true ensures data is available before any request is served
 */
@FnSchedule(cron = "0 0/5 * * *", runOnStart = true, timeoutMs = 60000)
@FnRoute(path = "/admin/cache/warm", methods = {"POST"})
@FnGroup("platform-ops")
public class CacheWarmerFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(CacheWarmerFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long start = System.currentTimeMillis();
        int loaded = 0;

        // 1. Load product catalog into heap — every function can read it zero-copy
        List<ProductEntry> catalog = fetchProductCatalog();
        ctx.heap().publish("ref:product-catalog", catalog);
        ctx.cache().put("product-catalog-version", String.valueOf(System.currentTimeMillis()));
        loaded += catalog.size();

        // 2. Load price tables
        Map<String, PriceTable> priceTables = fetchPriceTables();
        ctx.heap().publish("ref:price-tables", priceTables);
        loaded += priceTables.size();

        // 3. Load feature flags — functions check these instead of calling a flag service
        Map<String, Boolean> featureFlags = fetchFeatureFlags();
        ctx.heap().publish("ref:feature-flags", featureFlags);
        loaded += featureFlags.size();

        // 4. Load country/currency reference data
        Map<String, CurrencyInfo> currencies = fetchCurrencies();
        ctx.heap().publish("ref:currencies", currencies);
        loaded += currencies.size();

        long elapsed = System.currentTimeMillis() - start;
        LOG.info(String.format("Cache warm complete: %d entries loaded in %dms", loaded, elapsed));

        // Publish warm status so health checks can verify data freshness
        ctx.heap().publish("ops:cache-warm-status", new WarmStatus(
                Instant.now().toEpochMilli(), loaded, elapsed, true));

        return KubeFnResponse.ok(Map.of("loaded", loaded, "elapsedMs", elapsed));
    }

    // --- Simulated external data fetches (would be DB/API calls in production) ---

    private List<ProductEntry> fetchProductCatalog() {
        return List.of(
                new ProductEntry("SKU-001", "Widget Pro", 29.99, "electronics"),
                new ProductEntry("SKU-002", "Gadget Max", 49.99, "electronics"),
                new ProductEntry("SKU-003", "Basic Tee", 14.99, "apparel")
        );
    }

    private Map<String, PriceTable> fetchPriceTables() {
        return Map.of(
                "US", new PriceTable("USD", 1.0),
                "EU", new PriceTable("EUR", 0.92),
                "UK", new PriceTable("GBP", 0.79)
        );
    }

    private Map<String, Boolean> fetchFeatureFlags() {
        return Map.of("new-checkout", true, "dark-mode", false, "beta-search", true);
    }

    private Map<String, CurrencyInfo> fetchCurrencies() {
        return Map.of(
                "USD", new CurrencyInfo("US Dollar", "$", 2),
                "EUR", new CurrencyInfo("Euro", "\u20ac", 2),
                "JPY", new CurrencyInfo("Japanese Yen", "\u00a5", 0)
        );
    }

    public record ProductEntry(String sku, String name, double price, String category) {}
    public record PriceTable(String currency, double conversionRate) {}
    public record CurrencyInfo(String name, String symbol, int decimalPlaces) {}
    public record WarmStatus(long timestamp, int entriesLoaded, long elapsedMs, boolean success) {}
}
