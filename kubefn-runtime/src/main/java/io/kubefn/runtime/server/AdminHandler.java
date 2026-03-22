package io.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import io.kubefn.runtime.resilience.FunctionCircuitBreaker;
import io.kubefn.runtime.routing.FunctionRouter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint handler for health, readiness, introspection,
 * heap metrics, circuit breaker status, and audit log.
 */
public class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final FunctionRouter router;
    private final ObjectMapper objectMapper;
    private final HeapExchangeImpl heapExchange;
    private final FunctionCircuitBreaker circuitBreaker;

    public AdminHandler(FunctionRouter router, ObjectMapper objectMapper,
                        HeapExchangeImpl heapExchange, FunctionCircuitBreaker circuitBreaker) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.heapExchange = heapExchange;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String path = request.uri().split("\\?")[0];

        Object responseBody;
        int status = 200;

        switch (path) {
            case "/healthz" -> responseBody = Map.of(
                    "status", "alive",
                    "organism", "kubefn",
                    "version", "0.2.0"
            );

            case "/readyz" -> {
                boolean ready = router.routeCount() > 0;
                status = ready ? 200 : 503;
                responseBody = Map.of(
                        "status", ready ? "ready" : "no_functions_loaded",
                        "functionCount", router.routeCount()
                );
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
                var heapMetrics = heapExchange.metrics();
                var statusMap = new LinkedHashMap<String, Object>();
                statusMap.put("version", "0.2.0");
                statusMap.put("uptime_ms", runtime.getUptime());
                statusMap.put("heap_used_mb", memory.getHeapMemoryUsage().getUsed() / (1024 * 1024));
                statusMap.put("heap_max_mb", memory.getHeapMemoryUsage().getMax() / (1024 * 1024));
                statusMap.put("thread_count", ManagementFactory.getThreadMXBean().getThreadCount());
                statusMap.put("loaded_classes", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
                statusMap.put("route_count", router.routeCount());
                statusMap.put("jvm_version", runtime.getSpecVersion());
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
                        "keys", heapExchange.keys()
                );
            }

            case "/admin/breakers" -> responseBody = circuitBreaker.allStatus();

            default -> {
                status = 404;
                responseBody = Map.of("error", "Unknown admin endpoint: " + path, "status", 404);
            }
        }

        byte[] body = objectMapper.writeValueAsBytes(responseBody);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
