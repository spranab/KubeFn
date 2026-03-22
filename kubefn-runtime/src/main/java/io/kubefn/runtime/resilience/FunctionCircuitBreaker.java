package io.kubefn.runtime.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-function circuit breakers. If a function fails repeatedly,
 * the breaker trips and returns 503 instead of cascading failures.
 *
 * <p>This protects the shared JVM organism from one bad function
 * degrading all others.
 */
public class FunctionCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(FunctionCircuitBreaker.class);

    private final CircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public FunctionCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)           // Trip at 50% failure rate
                .minimumNumberOfCalls(5)             // Need at least 5 calls before evaluating
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)               // Over last 10 calls
                .waitDurationInOpenState(Duration.ofSeconds(30)) // Stay open 30s
                .permittedNumberOfCallsInHalfOpenState(3) // Allow 3 test calls
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        this.registry = CircuitBreakerRegistry.of(config);
    }

    /**
     * Get or create a circuit breaker for a function.
     * Key format: "group.function"
     */
    public CircuitBreaker getBreaker(String group, String function) {
        String key = group + "." + function;
        return breakers.computeIfAbsent(key, k -> {
            CircuitBreaker breaker = registry.circuitBreaker(k);
            breaker.getEventPublisher()
                    .onStateTransition(event -> {
                        log.warn("Circuit breaker state change: {} → {} for function {}",
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState(),
                                k);
                    });
            return breaker;
        });
    }

    /**
     * Check if a function's circuit breaker allows execution.
     */
    public boolean isCallPermitted(String group, String function) {
        CircuitBreaker breaker = getBreaker(group, function);
        return breaker.tryAcquirePermission();
    }

    /**
     * Record a successful call.
     */
    public void recordSuccess(String group, String function, long durationNanos) {
        getBreaker(group, function).onSuccess(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Record a failed call.
     */
    public void recordFailure(String group, String function, long durationNanos, Throwable error) {
        getBreaker(group, function).onError(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS, error);
    }

    /**
     * Get circuit breaker status for admin/observability.
     */
    public Map<String, BreakerStatus> allStatus() {
        Map<String, BreakerStatus> result = new ConcurrentHashMap<>();
        breakers.forEach((key, breaker) -> {
            var metrics = breaker.getMetrics();
            result.put(key, new BreakerStatus(
                    breaker.getState().name(),
                    metrics.getFailureRate(),
                    metrics.getNumberOfSuccessfulCalls(),
                    metrics.getNumberOfFailedCalls(),
                    metrics.getNumberOfNotPermittedCalls()
            ));
        });
        return result;
    }

    public record BreakerStatus(
            String state,
            float failureRate,
            int successfulCalls,
            int failedCalls,
            long notPermittedCalls
    ) {}
}
