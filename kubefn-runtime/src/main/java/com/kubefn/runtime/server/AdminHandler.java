package com.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.introspection.CausalCaptureEngine;
import com.kubefn.runtime.introspection.ReplayEngine;
import com.kubefn.runtime.metrics.KubeFnMetrics;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
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
    private final AdminAuth auth;
    private byte[] traceUiHtml;

    public AdminHandler(FunctionRouter router, ObjectMapper objectMapper,
                        HeapExchangeImpl heapExchange, FunctionCircuitBreaker circuitBreaker,
                        CausalCaptureEngine captureEngine, ReplayEngine replayEngine) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.heapExchange = heapExchange;
        this.circuitBreaker = circuitBreaker;
        this.captureEngine = captureEngine;
        this.replayEngine = replayEngine;
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
            case "/healthz" -> responseBody = Map.of(
                    "status", "alive", "organism", "kubefn", "version", "0.3.0");

            case "/readyz" -> {
                boolean ready = router.routeCount() > 0;
                status = ready ? 200 : 503;
                responseBody = Map.of("status", ready ? "ready" : "no_functions_loaded",
                        "functionCount", router.routeCount());
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

            case "/admin/breakers" -> responseBody = circuitBreaker.allStatus();
            case "/admin/metrics" -> responseBody = KubeFnMetrics.instance().snapshot();

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
