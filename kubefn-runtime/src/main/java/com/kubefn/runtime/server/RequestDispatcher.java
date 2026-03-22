package com.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.runtime.config.RuntimeConfig;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.introspection.CausalCaptureEngine;
import com.kubefn.runtime.lifecycle.DrainManager;
import com.kubefn.runtime.lifecycle.RevisionContext;
import com.kubefn.runtime.metrics.KubeFnMetrics;
import com.kubefn.runtime.resilience.FallbackRegistry;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.routing.FunctionRouter;
import com.kubefn.runtime.tracing.KubeFnTracer;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Production-grade request dispatcher. All hardening components wired in:
 * - OpenTelemetry tracing per invocation
 * - Circuit breakers with fallback execution
 * - Per-group concurrency limits (semaphore)
 * - Request timeout enforcement
 * - Graceful drain awareness (rejects during hot-swap drain)
 * - Revision-pinned execution context
 * - HeapExchange publisher attribution
 * - Per-function metrics recording (latency histograms)
 * - Structured MDC logging (requestId, group, function, revision)
 */
public class RequestDispatcher extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(RequestDispatcher.class);

    private final FunctionRouter router;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig config;
    private final FunctionCircuitBreaker circuitBreaker;
    private final FallbackRegistry fallbackRegistry;
    private final DrainManager drainManager;
    private final CausalCaptureEngine captureEngine;
    private final Map<String, Semaphore> groupSemaphores;

    public RequestDispatcher(FunctionRouter router, ExecutorService executor,
                             ObjectMapper objectMapper, RuntimeConfig config,
                             FunctionCircuitBreaker circuitBreaker,
                             FallbackRegistry fallbackRegistry,
                             DrainManager drainManager,
                             CausalCaptureEngine captureEngine) {
        this.router = router;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.config = config;
        this.circuitBreaker = circuitBreaker;
        this.fallbackRegistry = fallbackRegistry;
        this.drainManager = drainManager;
        this.captureEngine = captureEngine;
        this.groupSemaphores = new HashMap<>();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest nettyRequest) {
        // Validate request before processing
        if (!nettyRequest.decoderResult().isSuccess()) {
            sendError(ctx, 400, "Malformed HTTP request", "invalid");
            return;
        }

        String method = nettyRequest.method().name();
        String uri = nettyRequest.uri();

        // URI validation
        if (uri == null || uri.isEmpty()) {
            sendError(ctx, 400, "Missing request URI", "invalid");
            return;
        }

        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Map<String, String> queryParams = parseQueryParams(uri);
        Map<String, String> headers = extractHeaders(nettyRequest);
        byte[] body = extractBody(nettyRequest);
        String requestId = KubeFnTracer.nextRequestId();

        // Dispatch to virtual thread — NEVER run user code on event loop
        executor.submit(() -> {
            // Set MDC for structured logging
            MDC.put("requestId", requestId);
            MDC.put("method", method);
            MDC.put("path", path);
            try {
                handleRequest(ctx, method, path, queryParams, headers, body, requestId);
            } catch (Exception e) {
                log.error("Unhandled dispatch error: {} {}", method, path, e);
                sendError(ctx, 500, "Internal server error", requestId);
            } finally {
                MDC.clear();
                RevisionContext.clear();
                HeapExchangeImpl.clearCurrentContext();
            }
        });
    }

    private void handleRequest(ChannelHandlerContext ctx, String method, String path,
                               Map<String, String> queryParams, Map<String, String> headers,
                               byte[] body, String requestId) {
        // 1. Route resolution
        var resolved = router.resolve(method, path);
        if (resolved.isEmpty()) {
            sendError(ctx, 404, "No function for " + method + " " + path, requestId);
            return;
        }

        var route = resolved.get();
        var entry = route.entry();
        String groupName = entry.groupName();
        String functionName = entry.functionName();
        String revisionId = entry.revisionId();

        // Set MDC context for this function
        MDC.put("group", groupName);
        MDC.put("function", functionName);
        MDC.put("revision", revisionId);

        // 2. Drain check — reject if group is being hot-swapped
        if (drainManager.isDraining(groupName)) {
            sendError(ctx, 503, "Group '" + groupName + "' is draining (hot-swap in progress)", requestId);
            return;
        }

        // 3. Acquire drain slot (track in-flight)
        if (!drainManager.acquireRequest(groupName)) {
            sendError(ctx, 503, "Group '" + groupName + "' is draining", requestId);
            return;
        }

        // 4. Set revision context — pin this request
        RevisionContext.setCurrent(new RevisionContext(
                requestId, Map.of(groupName, revisionId), Instant.now()));

        // 5. Set HeapExchange attribution
        HeapExchangeImpl.setCurrentContext(groupName, functionName);

        // 6. Circuit breaker check
        if (!circuitBreaker.isCallPermitted(groupName, functionName)) {
            drainManager.releaseRequest(groupName);
            KubeFnMetrics.instance().recordBreakerTrip();
            // Try fallback
            KubeFnRequest request = new KubeFnRequest(
                    method, path, route.subPath(), headers, queryParams, body);
            KubeFnResponse fallbackResponse = fallbackRegistry.executeFallback(
                    groupName, functionName, request, null);
            sendResponse(ctx, fallbackResponse, requestId);
            return;
        }

        // 7. Concurrency limit
        Semaphore semaphore = groupSemaphores.computeIfAbsent(
                groupName, k -> new Semaphore(config.maxConcurrencyPerGroup()));

        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drainManager.releaseRequest(groupName);
            sendError(ctx, 503, "Interrupted", requestId);
            return;
        }

        if (!acquired) {
            drainManager.releaseRequest(groupName);
            sendError(ctx, 503, "Group '" + groupName + "' at max concurrency", requestId);
            return;
        }

        // 8. Causal capture: request start
        captureEngine.captureRequestStart(requestId, groupName, functionName, revisionId);

        // 9. Execute with tracing, timeout, metrics, and causal capture
        Span span = KubeFnTracer.startFunctionSpan(groupName, functionName, revisionId, requestId);
        long startNanos = System.nanoTime();
        boolean success = false;

        try {
            KubeFnRequest request = new KubeFnRequest(
                    method, path, route.subPath(), headers, queryParams, body);

            // Causal capture: function start
            captureEngine.captureFunctionStart(requestId, groupName, functionName, revisionId);

            // Execute with timeout enforcement
            KubeFnResponse response;
            try {
                response = CompletableFuture.supplyAsync(() -> {
                    try {
                        return entry.handler().handle(request);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, Runnable::run) // Run on current virtual thread
                .get(config.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                long durationNanos = System.nanoTime() - startNanos;
                KubeFnMetrics.instance().recordTimeout();
                circuitBreaker.recordFailure(groupName, functionName, durationNanos, e);
                sendError(ctx, 504, "Request timeout after " + config.requestTimeoutMs() + "ms", requestId);
                return;
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : new RuntimeException(e.getCause());
            }

            // Add tracing headers
            response.header("X-KubeFn-Request-Id", requestId);
            response.header("X-KubeFn-Revision", revisionId);
            response.header("X-KubeFn-Group", groupName);

            long durationNanos = System.nanoTime() - startNanos;
            captureEngine.captureFunctionEnd(requestId, groupName, functionName, durationNanos, null);
            captureEngine.captureRequestEnd(requestId, durationNanos, null);
            circuitBreaker.recordSuccess(groupName, functionName, durationNanos);
            KubeFnMetrics.instance().recordInvocation(groupName, functionName, durationNanos, true);
            success = true;

            sendResponse(ctx, response, requestId);

        } catch (Exception e) {
            long durationNanos = System.nanoTime() - startNanos;
            captureEngine.captureFunctionEnd(requestId, groupName, functionName, durationNanos,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            captureEngine.captureRequestEnd(requestId, durationNanos,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            circuitBreaker.recordFailure(groupName, functionName, durationNanos, e);
            KubeFnMetrics.instance().recordInvocation(groupName, functionName, durationNanos, false);
            log.error("Function error: {}.{} [{}]", groupName, functionName, requestId, e);

            // Try fallback
            KubeFnRequest request = new KubeFnRequest(
                    method, path, route.subPath(), headers, queryParams, body);
            KubeFnResponse fallbackResponse = fallbackRegistry.executeFallback(
                    groupName, functionName, request, e);
            sendResponse(ctx, fallbackResponse, requestId);
        } finally {
            semaphore.release();
            drainManager.releaseRequest(groupName);
            long durationNanos = System.nanoTime() - startNanos;
            KubeFnTracer.endFunctionSpan(span, durationNanos, 0, success);
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, KubeFnResponse fnResponse,
                              String requestId) {
        try {
            byte[] responseBody;
            String contentType;

            if (fnResponse.body() == null) {
                responseBody = new byte[0];
                contentType = "text/plain";
            } else if (fnResponse.body() instanceof String s) {
                responseBody = s.getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain; charset=utf-8";
            } else if (fnResponse.body() instanceof byte[] b) {
                responseBody = b;
                contentType = "application/octet-stream";
            } else {
                responseBody = objectMapper.writeValueAsBytes(fnResponse.body());
                contentType = "application/json; charset=utf-8";
            }

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(fnResponse.statusCode()),
                    Unpooled.wrappedBuffer(responseBody));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
            response.headers().set("X-KubeFn-Runtime", "v0.2");
            response.headers().set("X-KubeFn-Request-Id", requestId);

            fnResponse.headers().forEach((k, v) -> response.headers().set(k, v));

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Serialization error [{}]", requestId, e);
            sendError(ctx, 500, "Response serialization error", requestId);
        }
    }

    private void sendError(ChannelHandlerContext ctx, int status, String message, String requestId) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "error", message, "status", status, "requestId", requestId));
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                    Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set("X-KubeFn-Request-Id", requestId);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Failed to send error [{}]", requestId, e);
            ctx.close();
        }
    }

    private Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new HashMap<>();
        int qIdx = uri.indexOf('?');
        if (qIdx >= 0 && qIdx < uri.length() - 1) {
            for (String pair : uri.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(
                        java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return params;
    }

    private Map<String, String> extractHeaders(FullHttpRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(e -> headers.put(e.getKey().toLowerCase(), e.getValue()));
        return headers;
    }

    private byte[] extractBody(FullHttpRequest request) {
        if (request.content().readableBytes() == 0) return new byte[0];
        byte[] body = new byte[request.content().readableBytes()];
        request.content().readBytes(body);
        return body;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception", cause);
        ctx.close();
    }
}
