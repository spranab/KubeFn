package io.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.api.KubeFnRequest;
import io.kubefn.api.KubeFnResponse;
import io.kubefn.runtime.config.RuntimeConfig;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import io.kubefn.runtime.lifecycle.RevisionContext;
import io.kubefn.runtime.resilience.FunctionCircuitBreaker;
import io.kubefn.runtime.routing.FunctionRouter;
import io.kubefn.runtime.tracing.KubeFnTracer;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Netty channel handler that bridges HTTP requests to function invocations.
 * Dispatches to function handlers on virtual threads — NEVER on event loops.
 *
 * <p>v0.2 features:
 * - OpenTelemetry tracing per invocation
 * - Revision-pinned execution context
 * - Circuit breaker protection
 * - HeapExchange context attribution
 */
public class RequestDispatcher extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(RequestDispatcher.class);

    private final FunctionRouter router;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final RuntimeConfig config;
    private final FunctionCircuitBreaker circuitBreaker;
    private final Map<String, Semaphore> groupSemaphores;

    public RequestDispatcher(FunctionRouter router, ExecutorService executor,
                             ObjectMapper objectMapper, RuntimeConfig config,
                             FunctionCircuitBreaker circuitBreaker) {
        this.router = router;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.config = config;
        this.circuitBreaker = circuitBreaker;
        this.groupSemaphores = new HashMap<>();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest nettyRequest) {
        // Extract request info on event loop (fast)
        String method = nettyRequest.method().name();
        String uri = nettyRequest.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Map<String, String> queryParams = parseQueryParams(uri);
        Map<String, String> headers = extractHeaders(nettyRequest);
        byte[] body = extractBody(nettyRequest);

        // Generate request ID for tracing
        String requestId = KubeFnTracer.nextRequestId();

        // Dispatch to virtual thread — NEVER run user code on event loop
        executor.submit(() -> {
            try {
                handleRequest(ctx, method, path, queryParams, headers, body, requestId);
            } catch (Exception e) {
                log.error("Unhandled error dispatching request: {} {}", method, path, e);
                sendError(ctx, 500, "Internal server error", requestId);
            } finally {
                RevisionContext.clear();
                HeapExchangeImpl.clearCurrentContext();
            }
        });
    }

    private void handleRequest(ChannelHandlerContext ctx, String method, String path,
                               Map<String, String> queryParams, Map<String, String> headers,
                               byte[] body, String requestId) {
        // Resolve route
        var resolved = router.resolve(method, path);
        if (resolved.isEmpty()) {
            sendError(ctx, 404, "No function registered for " + method + " " + path, requestId);
            return;
        }

        var route = resolved.get();
        var entry = route.entry();
        String groupName = entry.groupName();
        String functionName = entry.functionName();
        String revisionId = entry.revisionId();

        // Set revision context — pin this request to current revision set
        RevisionContext revCtx = new RevisionContext(
                requestId,
                Map.of(groupName, revisionId),
                Instant.now()
        );
        RevisionContext.setCurrent(revCtx);

        // Set HeapExchange attribution context
        HeapExchangeImpl.setCurrentContext(groupName, functionName);

        // Circuit breaker check
        if (!circuitBreaker.isCallPermitted(groupName, functionName)) {
            sendError(ctx, 503,
                    "Circuit breaker OPEN for " + groupName + "." + functionName, requestId);
            return;
        }

        // Per-group concurrency limit (resource governance)
        Semaphore semaphore = groupSemaphores.computeIfAbsent(
                groupName, k -> new Semaphore(config.maxConcurrencyPerGroup()));

        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ctx, 503, "Service interrupted", requestId);
            return;
        }

        if (!acquired) {
            sendError(ctx, 503, "Group '" + groupName + "' at max concurrency", requestId);
            return;
        }

        // Start OpenTelemetry span
        Span span = KubeFnTracer.startFunctionSpan(groupName, functionName, revisionId, requestId);
        long startNanos = System.nanoTime();
        boolean success = false;

        try {
            // Build KubeFnRequest
            KubeFnRequest request = new KubeFnRequest(
                    method, path, route.subPath(), headers, queryParams, body);

            // Execute the function handler
            KubeFnResponse response = entry.handler().handle(request);

            // Add tracing headers to response
            response.header("X-KubeFn-Request-Id", requestId);
            response.header("X-KubeFn-Revision", revisionId);
            response.header("X-KubeFn-Group", groupName);

            // Record success
            long durationNanos = System.nanoTime() - startNanos;
            circuitBreaker.recordSuccess(groupName, functionName, durationNanos);
            success = true;

            // Send response
            sendResponse(ctx, response, requestId);

        } catch (Exception e) {
            long durationNanos = System.nanoTime() - startNanos;
            circuitBreaker.recordFailure(groupName, functionName, durationNanos, e);
            log.error("Function error: {}.{} [req={}] for {} {}",
                    groupName, functionName, requestId, method, path, e);
            sendError(ctx, 500, "Function error: " + e.getMessage(), requestId);
        } finally {
            semaphore.release();
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

            // Add custom headers from function
            fnResponse.headers().forEach((k, v) -> response.headers().set(k, v));

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

        } catch (Exception e) {
            log.error("Error serializing response [req={}]", requestId, e);
            sendError(ctx, 500, "Response serialization error", requestId);
        }
    }

    private void sendError(ChannelHandlerContext ctx, int status, String message, String requestId) {
        try {
            Map<String, Object> errorBody = Map.of(
                    "error", message,
                    "status", status,
                    "requestId", requestId
            );
            byte[] body = objectMapper.writeValueAsBytes(errorBody);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status),
                    Unpooled.wrappedBuffer(body));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set("X-KubeFn-Request-Id", requestId);

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Failed to send error response [req={}]", requestId, e);
            ctx.close();
        }
    }

    private Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new HashMap<>();
        int qIdx = uri.indexOf('?');
        if (qIdx >= 0 && qIdx < uri.length() - 1) {
            String query = uri.substring(qIdx + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    private Map<String, String> extractHeaders(FullHttpRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(entry -> headers.put(entry.getKey().toLowerCase(), entry.getValue()));
        return headers;
    }

    private byte[] extractBody(FullHttpRequest request) {
        if (request.content().readableBytes() == 0) {
            return new byte[0];
        }
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
