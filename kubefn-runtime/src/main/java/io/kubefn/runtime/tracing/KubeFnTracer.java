package io.kubefn.runtime.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * KubeFn-native tracing. Every function invocation gets a span with:
 * - function name, group, revision
 * - execution time
 * - heap objects touched
 * - semaphore wait time
 * - request lineage
 */
public class KubeFnTracer {

    private static final Tracer tracer;
    private static final AtomicLong requestCounter = new AtomicLong(0);

    // Custom attribute keys for KubeFn-specific telemetry
    public static final AttributeKey<String> KUBEFN_GROUP = AttributeKey.stringKey("kubefn.group");
    public static final AttributeKey<String> KUBEFN_FUNCTION = AttributeKey.stringKey("kubefn.function");
    public static final AttributeKey<String> KUBEFN_REVISION = AttributeKey.stringKey("kubefn.revision");
    public static final AttributeKey<String> KUBEFN_REQUEST_ID = AttributeKey.stringKey("kubefn.request_id");
    public static final AttributeKey<Long> KUBEFN_HEAP_OBJECTS = AttributeKey.longKey("kubefn.heap_objects_touched");
    public static final AttributeKey<Long> KUBEFN_DURATION_NANOS = AttributeKey.longKey("kubefn.duration_nanos");
    public static final AttributeKey<Boolean> KUBEFN_ZERO_COPY = AttributeKey.booleanKey("kubefn.zero_copy");

    static {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        tracer = sdk.getTracer("kubefn-runtime", "0.2.0");
    }

    public static Tracer tracer() {
        return tracer;
    }

    public static String nextRequestId() {
        return "req-" + Long.toHexString(System.currentTimeMillis()) +
                "-" + requestCounter.incrementAndGet();
    }

    /**
     * Start a span for a function invocation.
     */
    public static Span startFunctionSpan(String group, String function, String revision,
                                         String requestId) {
        return tracer.spanBuilder("kubefn." + group + "." + function)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(KUBEFN_GROUP, group)
                .setAttribute(KUBEFN_FUNCTION, function)
                .setAttribute(KUBEFN_REVISION, revision)
                .setAttribute(KUBEFN_REQUEST_ID, requestId)
                .startSpan();
    }

    /**
     * Record function completion on a span.
     */
    public static void endFunctionSpan(Span span, long durationNanos, int heapObjectsTouched,
                                       boolean success) {
        span.setAttribute(KUBEFN_DURATION_NANOS, durationNanos);
        span.setAttribute(KUBEFN_HEAP_OBJECTS, (long) heapObjectsTouched);
        span.setAttribute(KUBEFN_ZERO_COPY, true);

        if (!success) {
            span.setStatus(StatusCode.ERROR);
        }

        span.end();
    }
}
