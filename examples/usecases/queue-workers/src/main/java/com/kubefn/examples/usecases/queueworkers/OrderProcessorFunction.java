package com.kubefn.examples.usecases.queueworkers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * OrderProcessorFunction — Replaces a dedicated order-processing worker Deployment.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment (2-4 replicas) consuming from Kafka/SQS/RabbitMQ
 *   - Each replica runs its own JVM, its own connection pools, its own caches
 *   - To enrich an order, it makes network calls to pricing-service, inventory-service
 *   - Each call: DNS lookup -> TCP connect -> TLS -> serialize -> deserialize -> respond
 *   - Scaling requires HPA watching queue depth — slow to react (30-60s)
 *
 * AFTER (KubeFn):
 *   - One function with concurrency=4 handles the queue
 *   - Enrichment data (pricing, inventory) is already on the heap — zero-copy reads
 *   - No network calls for enrichment, no serialization, no connection pools
 *   - Processed orders are published to heap for downstream functions
 */
@FnQueue(topic = "orders", concurrency = 4, deadLetterTopics = {"orders-dlq"})
@FnGroup("order-processing")
public class OrderProcessorFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(OrderProcessorFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();
        // Parse order from queue message (simplified — real impl uses JSON parser)
        OrderMessage order = parseOrder(body);

        // 1. Validate order basics
        if (order.customerId() == null || order.items().isEmpty()) {
            LOG.warning("Invalid order rejected: " + order.orderId());
            return KubeFnResponse.error(Map.of("error", "invalid_order", "orderId", order.orderId()));
        }

        // 2. Enrich from heap — zero-copy reads, no network calls to other services
        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = ctx.heap()
                .get("ref:price-tables", Map.class)
                .orElseThrow(() -> new IllegalStateException("Price tables not on heap"));

        @SuppressWarnings("unchecked")
        Map<String, Boolean> featureFlags = ctx.heap()
                .get("ref:feature-flags", Map.class)
                .orElse(Map.of());

        // 3. Calculate total (using heap-resident pricing data)
        double total = 0;
        for (var item : order.items().entrySet()) {
            // In production: look up price from pricing data by SKU
            total += item.getValue() * 29.99; // simplified
        }

        // 4. Apply discount if feature flag is enabled
        boolean discountEnabled = Boolean.TRUE.equals(featureFlags.get("new-checkout"));
        if (discountEnabled && total > 100) {
            total *= 0.9; // 10% discount on orders over $100
        }

        // 5. Create processed order and publish to heap
        var processed = new ProcessedOrder(
                order.orderId(), order.customerId(), total,
                discountEnabled ? "DISCOUNT_APPLIED" : "STANDARD",
                Instant.now().toEpochMilli(), "CONFIRMED"
        );
        ctx.heap().publish("order:" + order.orderId(), processed);

        LOG.info(String.format("Order %s processed: $%.2f (%s)",
                order.orderId(), total, processed.pricingTier()));

        return KubeFnResponse.ok(Map.of(
                "orderId", order.orderId(),
                "total", total,
                "status", "CONFIRMED"
        ));
    }

    private OrderMessage parseOrder(String body) {
        // Simplified parser — real implementation would use Jackson/Gson
        return new OrderMessage(
                UUID.randomUUID().toString(),
                "customer-123",
                Map.of("SKU-001", 2, "SKU-003", 1)
        );
    }

    public record OrderMessage(String orderId, String customerId, Map<String, Integer> items) {}
    public record ProcessedOrder(String orderId, String customerId, double total,
                                 String pricingTier, long processedAt, String status) {}
}
