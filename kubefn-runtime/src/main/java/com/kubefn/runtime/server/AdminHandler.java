package com.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.memory.GCPressureMonitor;
import com.kubefn.runtime.memory.GroupMemoryBudget;
import com.kubefn.runtime.memory.MemoryCircuitBreaker;
import com.kubefn.runtime.heap.HeapLifecycle;
import com.kubefn.runtime.introspection.CausalCaptureEngine;
import com.kubefn.runtime.introspection.ReplayEngine;
import com.kubefn.runtime.metrics.KubeFnMetrics;
import com.kubefn.runtime.metrics.PrometheusExporter;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.resources.SharedResourceManager;
import com.kubefn.runtime.routing.FunctionRouter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint handler with full introspection support.
 * Serves health, metrics, traces, replay, and the web UI.
 */
public class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final FunctionRouter router;
    private final ObjectMapper objectMapper;
    private final HeapExchangeImpl heapExchange;
    private final FunctionCircuitBreaker circuitBreaker;
    private final CausalCaptureEngine captureEngine;
    private final ReplayEngine replayEngine;
    private final PrometheusExporter prometheusExporter;
    private final HeapLifecycle heapLifecycle;
    private final SharedResourceManager resourceManager;
    private final GroupMemoryBudget memoryBudget;
    private final MemoryCircuitBreaker memoryBreaker;
    private final GCPressureMonitor gcMonitor;
    private final com.kubefn.runtime.replay.CaptureStore captureStore;
    private final com.kubefn.runtime.replay.CapturePolicy capturePolicy;
    private final AdminAuth auth;
    private byte[] traceUiHtml;

    public AdminHandler(FunctionRouter router, ObjectMapper objectMapper,
                        HeapExchangeImpl heapExchange, FunctionCircuitBreaker circuitBreaker,
                        CausalCaptureEngine captureEngine, ReplayEngine replayEngine,
                        PrometheusExporter prometheusExporter, HeapLifecycle heapLifecycle,
                        SharedResourceManager resourceManager,
                        GroupMemoryBudget memoryBudget, MemoryCircuitBreaker memoryBreaker,
                        GCPressureMonitor gcMonitor,
                        com.kubefn.runtime.replay.CaptureStore captureStore,
                        com.kubefn.runtime.replay.CapturePolicy capturePolicy) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.heapExchange = heapExchange;
        this.circuitBreaker = circuitBreaker;
        this.captureEngine = captureEngine;
        this.replayEngine = replayEngine;
        this.prometheusExporter = prometheusExporter;
        this.heapLifecycle = heapLifecycle;
        this.resourceManager = resourceManager;
        this.memoryBudget = memoryBudget;
        this.memoryBreaker = memoryBreaker;
        this.gcMonitor = gcMonitor;
        this.captureStore = captureStore;
        this.capturePolicy = capturePolicy;
        this.auth = new AdminAuth();
        loadTraceUi();
    }

    private void loadTraceUi() {
        try (InputStream is = getClass().getResourceAsStream("/static/trace-ui.html")) {
            if (is != null) {
                traceUiHtml = is.readAllBytes();
            } else {
                traceUiHtml = "<html><body><h1>KubeFn Trace UI</h1><p>UI resource not found.</p></body></html>".getBytes();
            }
        } catch (Exception e) {
            traceUiHtml = "<html><body><h1>Error loading Trace UI</h1></body></html>".getBytes();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        String query = uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : "";
        String httpMethod = request.method().name();

        // Auth check for admin endpoints
        if (path.startsWith("/admin") && auth.isEnabled()) {
            String authHeader = request.headers().get("Authorization");
            if (!auth.isAuthorized(path, authHeader)) {
                sendJson(ctx, 401, objectMapper.writeValueAsBytes(
                        Map.of("error", "Unauthorized", "hint", "Set Authorization: Bearer <token>")));
                return;
            }
        }

        // Serve trace UI
        if (path.equals("/admin/ui") || path.equals("/admin/ui/")) {
            sendHtml(ctx, traceUiHtml);
            return;
        }

        Object responseBody;
        int status = 200;

        switch (path) {
            case "/healthz", "/admin/health" -> responseBody = Map.of(
                    "status", "UP", "organism", "kubefn", "version", "0.5.0",
                    "functions", router.routeCount(),
                    "uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());

            case "/readyz", "/admin/ready" -> {
                boolean ready = router.routeCount() > 0;
                status = ready ? 200 : 503;
                responseBody = Map.of("status", ready ? "READY" : "NOT_READY",
                        "functionCount", router.routeCount());
            }

            case "/admin/drain" -> {
                var drainMap = new LinkedHashMap<String, Object>();
                drainMap.put("draining", false);
                drainMap.put("inFlightRequests", 0);
                responseBody = drainMap;
            }

            case "/admin/scheduler" -> {
                // Return scheduled functions info from loaded functions with @FnSchedule
                var scheduled = new java.util.ArrayList<Map<String, Object>>();
                router.allRoutes().forEach((key, entry) -> {
                    try {
                        var cls = entry.handler().getClass();
                        var ann = cls.getAnnotation(com.kubefn.api.FnSchedule.class);
                        if (ann != null) {
                            var info = new LinkedHashMap<String, Object>();
                            info.put("function", entry.functionName());
                            info.put("group", entry.groupName());
                            info.put("cron", ann.cron());
                            info.put("timezone", ann.timezone());
                            info.put("runOnStart", ann.runOnStart());
                            info.put("timeoutMs", ann.timeoutMs());
                            info.put("path", key.path());
                            scheduled.add(info);
                        }
                    } catch (Exception ignored) {}
                });
                responseBody = Map.of("scheduled", scheduled, "count", scheduled.size());
            }

            case "/admin/functions" -> {
                var functions = new java.util.ArrayList<Map<String, String>>();
                router.allRoutes().forEach((key, entry) -> {
                    var fn = new LinkedHashMap<String, String>();
                    fn.put("method", key.method());
                    fn.put("path", key.path());
                    fn.put("group", entry.groupName());
                    fn.put("function", entry.functionName());
                    fn.put("class", entry.className());
                    fn.put("revision", entry.revisionId());
                    functions.add(fn);
                });
                responseBody = Map.of("functions", functions, "count", functions.size());
            }

            case "/admin/status" -> {
                var runtime = ManagementFactory.getRuntimeMXBean();
                var memory = ManagementFactory.getMemoryMXBean();
                var statusMap = new LinkedHashMap<String, Object>();
                statusMap.put("version", "0.3.0");
                statusMap.put("uptime_ms", runtime.getUptime());
                statusMap.put("heap_used_mb", memory.getHeapMemoryUsage().getUsed() / (1024 * 1024));
                statusMap.put("heap_max_mb", memory.getHeapMemoryUsage().getMax() / (1024 * 1024));
                statusMap.put("thread_count", ManagementFactory.getThreadMXBean().getThreadCount());
                statusMap.put("loaded_classes", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
                statusMap.put("route_count", router.routeCount());
                statusMap.put("jvm_version", runtime.getSpecVersion());
                statusMap.put("causal_events", captureEngine.ring().size());
                responseBody = statusMap;
            }

            case "/admin/heap" -> {
                var metrics = heapExchange.metrics();
                responseBody = Map.of(
                        "objectCount", metrics.objectCount(),
                        "publishCount", metrics.publishCount(),
                        "getCount", metrics.getCount(),
                        "hitCount", metrics.hitCount(),
                        "missCount", metrics.missCount(),
                        "hitRate", String.format("%.2f%%", metrics.hitRate() * 100),
                        "keys", heapExchange.keys());
            }

            case "/admin/heap/trace" -> {
                var trace = heapExchange.trace();
                String traceKey = parseParam(query, "key");
                String traceReq = parseParam(query, "request");
                String traceFunc = parseParam(query, "function");
                int traceLimit = parseIntParam(query, "limit", 50);

                java.util.List<?> entries;
                if (traceKey != null) {
                    entries = trace.forKey(traceKey).stream()
                            .map(com.kubefn.runtime.heap.HeapTrace.TraceEntry::toMap).toList();
                } else if (traceReq != null) {
                    entries = trace.forRequest(traceReq).stream()
                            .map(com.kubefn.runtime.heap.HeapTrace.TraceEntry::toMap).toList();
                } else if (traceFunc != null) {
                    entries = trace.forFunction(traceFunc).stream()
                            .map(com.kubefn.runtime.heap.HeapTrace.TraceEntry::toMap).toList();
                } else {
                    entries = trace.recent(traceLimit).stream()
                            .map(com.kubefn.runtime.heap.HeapTrace.TraceEntry::toMap).toList();
                }
                responseBody = Map.of("entries", entries, "count", entries.size(),
                        "trace", trace.status());
            }

            case "/admin/heap/diff" -> {
                var trace = heapExchange.trace();
                String fromStr = parseParam(query, "from");
                String toStr = parseParam(query, "to");
                if (fromStr != null && toStr != null) {
                    var from = java.time.Instant.parse(fromStr);
                    var to = java.time.Instant.parse(toStr);
                    responseBody = trace.snapshotDiff(from, to);
                } else {
                    responseBody = Map.of("error", "Provide ?from=ISO&to=ISO timestamps",
                            "example", "/admin/heap/diff?from=2026-03-27T10:00:00Z&to=2026-03-27T10:05:00Z");
                }
            }

            case "/admin/heap/graph" -> {
                var graph = com.kubefn.runtime.graph.HeapDependencyGraph.buildFrom(heapExchange.trace());
                String fn = parseParam(query, "function");
                if (fn != null) {
                    responseBody = graph.impactAnalysis(fn);
                } else {
                    responseBody = graph.toMap();
                }
            }

            case "/admin/heap/graph/ascii" -> {
                var graph = com.kubefn.runtime.graph.HeapDependencyGraph.buildFrom(heapExchange.trace());
                String ascii = graph.renderAscii();
                byte[] body = ascii.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                sendPlainText(ctx, body);
                return;
            }

            case "/admin/breakers" -> responseBody = circuitBreaker.allStatus();
            case "/admin/metrics" -> responseBody = KubeFnMetrics.instance().snapshot();

            case "/admin/lifecycle" -> responseBody = heapLifecycle.metrics();

            case "/admin/resources" -> responseBody = resourceManager.listResources();

            case "/admin/memory" -> responseBody = memoryBudget != null
                    ? memoryBudget.getStatus() : Map.of("error", "Memory budgets not enabled");

            case "/admin/gc" -> responseBody = gcMonitor != null
                    ? gcMonitor.getStatus() : Map.of("error", "GC monitor not enabled");

            case "/admin/captures" -> {
                if (captureStore != null) {
                    int limit = parseIntParam(query, "limit", 20);
                    String fn = parseParam(query, "function");
                    String level = parseParam(query, "level");
                    java.util.List<?> captures;
                    if ("value".equals(level)) {
                        captures = captureStore.recentValues(limit).stream()
                                .map(com.kubefn.runtime.replay.InvocationCapture::toMap).toList();
                    } else if (fn != null) {
                        captures = captureStore.forFunction(fn, limit).stream()
                                .map(com.kubefn.runtime.replay.InvocationCapture::toMap).toList();
                    } else {
                        captures = captureStore.recent(limit).stream()
                                .map(com.kubefn.runtime.replay.InvocationCapture::toMap).toList();
                    }
                    responseBody = Map.of("captures", captures, "store", captureStore.status());
                } else {
                    responseBody = Map.of("error", "Capture store not enabled");
                }
            }

            case "/admin/captures/failures" -> {
                if (captureStore != null) {
                    int limit = parseIntParam(query, "limit", 20);
                    var failures = captureStore.failures(limit).stream()
                            .map(com.kubefn.runtime.replay.InvocationCapture::toMap).toList();
                    responseBody = Map.of("failures", failures, "count", failures.size());
                } else {
                    responseBody = Map.of("error", "Capture store not enabled");
                }
            }

            case "/admin/captures/policy" -> responseBody = capturePolicy != null
                    ? capturePolicy.status() : Map.of("error", "Capture policy not enabled");

            case "/admin/replay" -> {
                if (captureStore != null && "POST".equals(httpMethod)) {
                    String invocationId = parseParam(query, "id");
                    if (invocationId != null) {
                        // Replay single invocation
                        var capture = captureStore.findById(invocationId);
                        if (capture.isPresent()) {
                            var executor = new com.kubefn.runtime.replay.ReplayExecutor(router, objectMapper);
                            var result = executor.replay(capture.get());
                            responseBody = result.toMap();
                        } else {
                            responseBody = Map.of("error", "Invocation not found: " + invocationId);
                        }
                    } else {
                        // Replay all VALUE captures (batch validation)
                        int limit = parseIntParam(query, "limit", 100);
                        var captures = captureStore.recentValues(limit);
                        var executor = new com.kubefn.runtime.replay.ReplayExecutor(router, objectMapper);
                        var result = executor.replayBatch(captures);
                        responseBody = result.toMap();
                    }
                } else {
                    responseBody = Map.of(
                            "usage", "POST /admin/replay?id=<invocationId> to replay one",
                            "batch", "POST /admin/replay to replay all VALUE captures",
                            "valueCapturesAvailable", captureStore != null ? captureStore.recentValues(1).size() : 0
                    );
                }
            }

            case "/admin/promote/status" -> {
                if (captureStore != null && capturePolicy != null) {
                    var gate = new com.kubefn.runtime.replay.PromotionGate(
                            captureStore, router, objectMapper);
                    responseBody = Map.of(
                            "gate", gate.status(),
                            "policy", capturePolicy.status(),
                            "store", captureStore.status()
                    );
                } else {
                    responseBody = Map.of("error", "Promotion gate not available");
                }
            }

            case "/admin/memory/breaker" -> responseBody = memoryBreaker != null
                    ? memoryBreaker.getStatus() : Map.of("error", "Memory breaker not enabled");

            case "/admin/graph" -> {
                var diagnostics = heapExchange.diagnostics();
                responseBody = diagnostics != null ? diagnostics.dependencyGraph()
                        : Map.of("error", "Diagnostics not initialized");
            }

            case "/admin/prometheus" -> {
                // Prometheus text format — different content type
                byte[] promBody = prometheusExporter.export().getBytes();
                FullHttpResponse promResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(promBody));
                promResponse.headers().set(HttpHeaderNames.CONTENT_TYPE,
                        "text/plain; version=0.0.4; charset=utf-8");
                promResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, promBody.length);
                ctx.writeAndFlush(promResponse).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            // Causal Introspection
            case "/admin/traces/recent" -> {
                int limit = parseIntParam(query, "limit", 50);
                responseBody = captureEngine.recentTraces(limit);
            }

            case "/admin/traces/search" -> {
                String group = parseParam(query, "group");
                String function = parseParam(query, "function");
                double minMs = parseDoubleParam(query, "minDurationMs", 0);
                boolean hasErrors = "true".equals(parseParam(query, "hasErrors"));
                int limit = parseIntParam(query, "limit", 20);
                responseBody = captureEngine.searchTraces(group, function, minMs, hasErrors, limit);
            }

            default -> {
                if (path.startsWith("/admin/traces/")) {
                    String requestId = path.substring("/admin/traces/".length());
                    var trace = captureEngine.getTrace(requestId);
                    responseBody = trace != null ? trace :
                            Map.of("error", "Trace not found: " + requestId);
                    status = trace != null ? 200 : 404;
                } else if (path.startsWith("/admin/replay/") && "POST".equals(httpMethod)) {
                    String requestId = path.substring("/admin/replay/".length());
                    responseBody = replayEngine.replay(requestId);
                } else {
                    status = 404;
                    responseBody = Map.of("error", "Unknown: " + path,
                            "hint", "Try /admin/ui for the trace dashboard");
                }
            }
        }

        byte[] body = objectMapper.writeValueAsBytes(responseBody);
        sendJson(ctx, status, body);
    }

    private void sendJson(ChannelHandlerContext ctx, int status, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendPlainText(ChannelHandlerContext ctx, byte[] text) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(text));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, text.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendHtml(ChannelHandlerContext ctx, byte[] html) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(html));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, html.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String parseParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private int parseIntParam(String query, String key, int def) {
        String v = parseParam(query, key);
        try { return v != null ? Integer.parseInt(v) : def; } catch (Exception e) { return def; }
    }

    private double parseDoubleParam(String query, String key, double def) {
        String v = parseParam(query, key);
        try { return v != null ? Double.parseDouble(v) : def; } catch (Exception e) { return def; }
    }
}
