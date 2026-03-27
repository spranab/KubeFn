package com.kubefn.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.runtime.classloader.FunctionLoader;
import com.kubefn.runtime.config.RuntimeConfig;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.heap.HeapLifecycle;
import com.kubefn.runtime.introspection.CausalCaptureEngine;
import com.kubefn.runtime.introspection.CausalEventRing;
import com.kubefn.runtime.introspection.ReplayEngine;
import com.kubefn.runtime.lifecycle.DrainManager;
import com.kubefn.runtime.memory.GCPressureMonitor;
import com.kubefn.runtime.memory.GroupMemoryBudget;
import com.kubefn.runtime.memory.MemoryCircuitBreaker;
import com.kubefn.runtime.metrics.KubeFnMetrics;
import com.kubefn.runtime.metrics.PrometheusExporter;
import com.kubefn.runtime.resilience.FallbackRegistry;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.resources.SharedResourceManager;
import com.kubefn.runtime.routing.FunctionRouter;
import com.kubefn.runtime.server.AdminHandler;
import com.kubefn.runtime.server.NettyServer;
import com.kubefn.runtime.watcher.FunctionWatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * KubeFn Runtime — Enterprise-grade entry point.
 *
 * <p>All systems wired into the live execution path:
 * HeapExchange + Guard + AuditLog + Lifecycle (TTL/eviction),
 * DrainManager, CircuitBreaker + Fallback,
 * SharedResourceManager (connection pools),
 * Metrics + PrometheusExporter,
 * Tracing + CausalIntrospection + Replay,
 * Multi-Revision Manager.
 */
public class KubeFnMain {

    private static final Logger log = LoggerFactory.getLogger(KubeFnMain.class);
    private static EventLoopGroup adminBossGroup;
    private static EventLoopGroup adminWorkerGroup;

    public static void main(String[] args) throws Exception {
        log.info("Booting KubeFn organism...");

        RuntimeConfig config = RuntimeConfig.fromEnv();

        // ── Core components ─────────────────────────────────────
        HeapExchangeImpl heapExchange = new HeapExchangeImpl();
        FunctionRouter router = new FunctionRouter();
        FunctionCircuitBreaker circuitBreaker = new FunctionCircuitBreaker();
        FallbackRegistry fallbackRegistry = new FallbackRegistry();
        DrainManager drainManager = new DrainManager();

        // ── Heap diagnostics (smart error messages on miss) ────────
        com.kubefn.runtime.heap.HeapDiagnostics heapDiagnostics = new com.kubefn.runtime.heap.HeapDiagnostics();
        heapExchange.setDiagnostics(heapDiagnostics);

        // ── Heap lifecycle (TTL eviction, request-scoped cleanup, memory pressure) ──
        HeapLifecycle heapLifecycle = new HeapLifecycle(heapExchange, heapExchange.guard());
        // Configure default TTLs from environment
        String defaultTTL = System.getenv("KUBEFN_HEAP_DEFAULT_TTL_MS");
        if (defaultTTL != null) {
            heapLifecycle.setDefaultTTL(Long.parseLong(defaultTTL));
        }

        // ── Per-group memory budgets ──────────────────────────────
        long defaultBudgetMB = Long.parseLong(System.getenv().getOrDefault(
                "KUBEFN_GROUP_MEMORY_BUDGET_MB", "256"));
        GroupMemoryBudget memoryBudget = new GroupMemoryBudget(defaultBudgetMB * 1024 * 1024);

        // ── Memory circuit breaker ────────────────────────────────
        long cooldownMs = Long.parseLong(System.getenv().getOrDefault(
                "KUBEFN_MEMORY_BREAKER_COOLDOWN_MS", "30000"));
        MemoryCircuitBreaker memoryBreaker = new MemoryCircuitBreaker(memoryBudget, cooldownMs);

        // ── GC pressure monitor ───────────────────────────────────
        GCPressureMonitor gcMonitor = new GCPressureMonitor(memoryBudget);
        gcMonitor.start();

        // ── Shared resource manager (connection pools, HTTP clients) ──
        SharedResourceManager resourceManager = new SharedResourceManager();

        // ── Causal introspection ────────────────────────────────
        CausalCaptureEngine captureEngine = new CausalCaptureEngine(
                new CausalEventRing(100_000));
        ReplayEngine replayEngine = new ReplayEngine(captureEngine, router);
        heapExchange.setCaptureEngine(captureEngine);

        // ── Prometheus metrics exporter ─────────────────────────
        PrometheusExporter prometheusExporter = new PrometheusExporter(
                KubeFnMetrics.instance(), heapExchange, circuitBreaker, router);

        // ── Function loader (now with shared resource manager) ──
        FunctionLoader loader = new FunctionLoader(
                router, heapExchange, drainManager, resourceManager);

        // ── HTTP server ─────────────────────────────────────────
        NettyServer server = new NettyServer(
                config, router, circuitBreaker, fallbackRegistry,
                drainManager, captureEngine, heapLifecycle);
        server.start();

        // ── Admin server ────────────────────────────────────────
        startAdminServer(config, router, server.objectMapper(),
                heapExchange, circuitBreaker, captureEngine, replayEngine,
                prometheusExporter, heapLifecycle, resourceManager,
                memoryBudget, memoryBreaker, gcMonitor,
                server.captureStore(), server.capturePolicy());

        // ── Load functions ──────────────────────────────────────
        if (Files.exists(config.functionsDir())) {
            loader.loadAll(config.functionsDir());
        } else {
            Files.createDirectories(config.functionsDir());
            log.info("Created functions directory: {}", config.functionsDir());
        }

        // ── Hot-reload watcher ──────────────────────────────────
        FunctionWatcher watcher = new FunctionWatcher(config.functionsDir(), loader);
        Thread.startVirtualThread(watcher);

        // ── Wire memory breaker to classloader unload ────────────
        memoryBreaker.setGroupUnloader(groupName -> {
            log.warn("Memory breaker unloading group: {}", groupName);
            router.unregisterGroup(groupName);
        });

        log.info("KubeFn organism is ALIVE. {} routes registered.", router.routeCount());
        log.info("Per-group memory budget: {}MB | Breaker cooldown: {}ms",
                defaultBudgetMB, cooldownMs);
        log.info("Trace UI: http://localhost:{}/admin/ui", config.adminPort());
        log.info("Prometheus: http://localhost:{}/admin/prometheus", config.adminPort());
        log.info("Drop function JARs into {} to deploy.", config.functionsDir());

        // ── Graceful shutdown ───────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining organism...");
            watcher.stop();
            gcMonitor.stop();
            heapLifecycle.shutdown();
            resourceManager.shutdown();
            server.stop();
            if (adminBossGroup != null) adminBossGroup.shutdownGracefully();
            if (adminWorkerGroup != null) adminWorkerGroup.shutdownGracefully();
            log.info("Organism shut down gracefully.");
        }, "kubefn-shutdown"));

        server.awaitTermination();
    }

    private static void startAdminServer(RuntimeConfig config, FunctionRouter router,
                                         ObjectMapper objectMapper, HeapExchangeImpl heapExchange,
                                         FunctionCircuitBreaker circuitBreaker,
                                         CausalCaptureEngine captureEngine,
                                         ReplayEngine replayEngine,
                                         PrometheusExporter prometheusExporter,
                                         HeapLifecycle heapLifecycle,
                                         SharedResourceManager resourceManager,
                                         GroupMemoryBudget memoryBudget,
                                         MemoryCircuitBreaker memoryBreaker,
                                         GCPressureMonitor gcMonitor,
                                         com.kubefn.runtime.replay.CaptureStore captureStore,
                                         com.kubefn.runtime.replay.CapturePolicy capturePolicy)
            throws InterruptedException {
        adminBossGroup = new NioEventLoopGroup(1);
        adminWorkerGroup = new NioEventLoopGroup(1);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(adminBossGroup, adminWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new AdminHandler(
                                router, objectMapper, heapExchange, circuitBreaker,
                                captureEngine, replayEngine, prometheusExporter,
                                heapLifecycle, resourceManager,
                                memoryBudget, memoryBreaker, gcMonitor,
                                captureStore, capturePolicy));
                    }
                });

        bootstrap.bind(config.adminPort()).sync();
        log.info("Admin server listening on port {}", config.adminPort());
    }
}
