package com.kubefn.examples.usecases.sidecars;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ConfigWatcherFunction — Replaces config-reloader sidecar containers.
 *
 * BEFORE (traditional K8s):
 *   - Each pod has a config sidecar (e.g., configmap-reload, vault-agent)
 *   - 50 pods = 50 sidecar containers watching the same ConfigMap/Vault path
 *   - Each sidecar independently polls, parses, and caches config
 *   - Config changes trigger rolling restarts or sidecar-mediated reload
 *   - Vault agent sidecars alone consume 32-64MB per pod
 *
 * AFTER (KubeFn):
 *   - One function polls config sources every minute + on startup
 *   - Publishes current config to heap — ALL functions read it zero-copy
 *   - Config changes are visible to every function instantly (next heap read)
 *   - No sidecars, no restarts, no per-pod polling
 */
@FnSchedule(cron = "0 0/1 * * *", runOnStart = true, timeoutMs = 15000)
@FnRoute(path = "/admin/config", methods = {"GET", "POST"})
@FnGroup("platform-ops")
public class ConfigWatcherFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(ConfigWatcherFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // GET: return current config from heap
        if ("GET".equals(request.method())) {
            return getCurrentConfig();
        }

        // POST or scheduled: reload config from sources
        return reloadConfig();
    }

    private KubeFnResponse reloadConfig() {
        long start = System.currentTimeMillis();

        // 1. Load config from multiple sources (simulated)
        Map<String, String> appConfig = loadAppConfig();
        Map<String, String> secrets = loadSecrets();
        Map<String, String> featureToggles = loadFeatureToggles();

        // 2. Detect changes by comparing with current heap config
        @SuppressWarnings("unchecked")
        Map<String, String> previousConfig = ctx.heap()
                .get("config:app", Map.class)
                .orElse(Map.of());

        int changedKeys = 0;
        for (var entry : appConfig.entrySet()) {
            String prev = previousConfig.get(entry.getKey());
            if (prev == null || !prev.equals(entry.getValue())) {
                changedKeys++;
                LOG.info(String.format("Config changed: %s = %s -> %s",
                        entry.getKey(), prev, entry.getValue()));
            }
        }

        // 3. Publish all configs to heap — every function reads these zero-copy
        ctx.heap().publish("config:app", appConfig);
        ctx.heap().publish("config:secrets", secrets);
        ctx.heap().publish("config:feature-toggles", featureToggles);

        // 4. Publish a combined "effective config" for convenience
        Map<String, String> effective = new HashMap<>();
        effective.putAll(appConfig);
        effective.putAll(featureToggles);
        // Note: secrets are published separately for access control
        ctx.heap().publish("config:effective", effective);

        // 5. Publish reload status for observability
        long elapsed = System.currentTimeMillis() - start;
        var status = new ConfigReloadStatus(
                Instant.now().toEpochMilli(), changedKeys,
                appConfig.size() + secrets.size() + featureToggles.size(),
                elapsed, true);
        ctx.heap().publish("ops:config-reload-status", status);

        if (changedKeys > 0) {
            LOG.info(String.format("Config reloaded: %d keys changed (%dms)", changedKeys, elapsed));
        } else {
            LOG.fine(String.format("Config reloaded: no changes (%dms)", elapsed));
        }

        return KubeFnResponse.ok(Map.of(
                "reloaded", true, "changedKeys", changedKeys, "elapsedMs", elapsed));
    }

    private KubeFnResponse getCurrentConfig() {
        @SuppressWarnings("unchecked")
        Map<String, String> effective = ctx.heap()
                .get("config:effective", Map.class)
                .orElse(Map.of());

        var reloadStatus = ctx.heap()
                .get("ops:config-reload-status", ConfigReloadStatus.class)
                .orElse(null);

        return KubeFnResponse.ok(Map.of(
                "config", effective,
                "lastReload", reloadStatus != null ? reloadStatus.timestamp() : 0
        ));
    }

    // --- Simulated config sources (real impl reads ConfigMaps, Vault, etc.) ---

    private Map<String, String> loadAppConfig() {
        return Map.of(
                "db.pool.size", "20",
                "db.timeout.ms", "5000",
                "http.max-connections", "100",
                "log.level", "INFO",
                "cors.allowed-origins", "https://app.example.com"
        );
    }

    private Map<String, String> loadSecrets() {
        return Map.of(
                "db.password", "***masked***",
                "stripe.api-key", "sk_***masked***",
                "jwt.signing-key", "***masked***"
        );
    }

    private Map<String, String> loadFeatureToggles() {
        return Map.of(
                "feature.new-checkout", "true",
                "feature.dark-mode", "false",
                "feature.beta-search", "true"
        );
    }

    public record ConfigReloadStatus(long timestamp, int changedKeys,
                                     int totalKeys, long elapsedMs, boolean success) {}
}
