package com.kubefn.examples.usecases.lifecycle;

import com.kubefn.api.*;
import com.kubefn.api.FnLifecyclePhase;
import com.kubefn.api.FnLifecyclePhase.Phase;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * CachePreloaderFunction — Replaces Kubernetes init containers.
 *
 * BEFORE (traditional K8s):
 *   - Init containers run before the main container starts
 *   - Each pod has its own init containers — 50 pods = 50x the initialization work
 *   - Init containers: check DB connectivity, run migrations, preload caches
 *   - If init fails, the pod restarts — CrashLoopBackOff for the whole pod
 *   - Init containers are separate images — own build pipeline, own security scanning
 *   - Total cold start: pull init image (5-30s) + run init (10-60s) + pull app image + start
 *
 * AFTER (KubeFn):
 *   - A lifecycle function with Phase.INIT runs once when the organism starts
 *   - Preloads all reference data into HeapExchange before ANY request is served
 *   - One initialization, shared by all functions — not repeated per-pod
 *   - If preloading fails, the organism won't accept traffic (health check fails)
 *   - order=1 ensures this runs before other INIT functions
 */
@FnLifecyclePhase(phase = Phase.INIT, order = 1)
@FnRoute(path = "/admin/preload", methods = {"POST"})
@FnGroup("platform-ops")
public class CachePreloaderFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(CachePreloaderFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long start = System.currentTimeMillis();
        List<PreloadStep> steps = new ArrayList<>();

        // Step 1: Verify database connectivity
        steps.add(verifyDatabaseConnectivity());

        // Step 2: Preload user permission mappings
        steps.add(preloadPermissions());

        // Step 3: Preload product catalog
        steps.add(preloadProductCatalog());

        // Step 4: Preload geo/region data
        steps.add(preloadGeoData());

        // Step 5: Preload rate limit configurations
        steps.add(preloadRateLimitConfig());

        // Step 6: Warm the local FnCache with hot keys
        steps.add(warmLocalCache());

        // Check if all steps succeeded
        boolean allSuccess = steps.stream().allMatch(PreloadStep::success);
        long elapsed = System.currentTimeMillis() - start;

        // Publish initialization status to heap — health check functions read this
        var status = new InitStatus(
                Instant.now().toEpochMilli(), allSuccess, steps, elapsed,
                steps.stream().filter(PreloadStep::success).count(),
                steps.size()
        );
        ctx.heap().publish("ops:init-status", status);

        if (!allSuccess) {
            List<String> failures = steps.stream()
                    .filter(s -> !s.success())
                    .map(PreloadStep::name)
                    .toList();
            LOG.severe("Preload FAILED steps: " + failures);
            return KubeFnResponse.error(Map.of(
                    "status", "INIT_FAILED", "failures", failures, "elapsedMs", elapsed));
        }

        LOG.info(String.format("Organism preload complete: %d/%d steps OK (%dms)",
                steps.size(), steps.size(), elapsed));

        return KubeFnResponse.ok(Map.of(
                "status", "INIT_COMPLETE", "steps", steps.size(), "elapsedMs", elapsed));
    }

    private PreloadStep verifyDatabaseConnectivity() {
        try {
            // Simulated DB connectivity check — real impl opens a JDBC connection
            LOG.info("Verifying database connectivity...");
            // In production: dataSource.getConnection().isValid(5)
            return new PreloadStep("database-connectivity", true, 15, "Connected to primary RW");
        } catch (Exception e) {
            return new PreloadStep("database-connectivity", false, 0, e.getMessage());
        }
    }

    private PreloadStep preloadPermissions() {
        long start = System.currentTimeMillis();
        // Simulated permission data — real impl queries IAM/auth DB
        Map<String, List<String>> permissions = Map.of(
                "role:admin", List.of("read", "write", "delete", "manage-users"),
                "role:editor", List.of("read", "write"),
                "role:viewer", List.of("read")
        );
        ctx.heap().publish("ref:role-permissions", permissions);
        long elapsed = System.currentTimeMillis() - start;
        return new PreloadStep("permissions", true, elapsed,
                permissions.size() + " roles loaded");
    }

    private PreloadStep preloadProductCatalog() {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> catalog = List.of(
                Map.of("sku", "SKU-001", "name", "Widget Pro", "price", 29.99, "active", true),
                Map.of("sku", "SKU-002", "name", "Gadget Max", "price", 49.99, "active", true),
                Map.of("sku", "SKU-003", "name", "Basic Tee", "price", 14.99, "active", true)
        );
        ctx.heap().publish("ref:product-catalog", catalog);
        long elapsed = System.currentTimeMillis() - start;
        return new PreloadStep("product-catalog", true, elapsed,
                catalog.size() + " products loaded");
    }

    private PreloadStep preloadGeoData() {
        long start = System.currentTimeMillis();
        Map<String, GeoRegion> regions = Map.of(
                "us-east-1", new GeoRegion("US East", "Virginia", "USD", -5),
                "eu-west-1", new GeoRegion("EU West", "Ireland", "EUR", 0),
                "ap-southeast-1", new GeoRegion("AP Southeast", "Singapore", "SGD", 8)
        );
        ctx.heap().publish("ref:geo-regions", regions);
        long elapsed = System.currentTimeMillis() - start;
        return new PreloadStep("geo-data", true, elapsed,
                regions.size() + " regions loaded");
    }

    private PreloadStep preloadRateLimitConfig() {
        long start = System.currentTimeMillis();
        Map<String, RateLimitTier> tiers = Map.of(
                "free", new RateLimitTier(60, 60_000),
                "premium", new RateLimitTier(300, 60_000),
                "enterprise", new RateLimitTier(1000, 60_000)
        );
        ctx.heap().publish("ref:ratelimit-config", tiers);
        long elapsed = System.currentTimeMillis() - start;
        return new PreloadStep("ratelimit-config", true, elapsed,
                tiers.size() + " tiers loaded");
    }

    private PreloadStep warmLocalCache() {
        long start = System.currentTimeMillis();
        // Pre-populate FnCache with frequently accessed keys
        ctx.cache().put("cache-version", String.valueOf(Instant.now().toEpochMilli()));
        ctx.cache().put("cache-warmed", "true");
        long elapsed = System.currentTimeMillis() - start;
        return new PreloadStep("local-cache-warm", true, elapsed, "FnCache warmed");
    }

    public record PreloadStep(String name, boolean success, long elapsedMs, String detail) {}
    public record GeoRegion(String displayName, String location, String currency, int utcOffset) {}
    public record RateLimitTier(int requestsPerWindow, long windowMs) {}
    public record InitStatus(long timestamp, boolean allSuccess, List<PreloadStep> steps,
                             long totalElapsedMs, long successCount, long totalCount) {}
}
