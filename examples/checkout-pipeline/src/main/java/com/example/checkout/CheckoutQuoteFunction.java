package com.example.checkout;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The final assembly function. Reads all intermediate results from HeapExchange
 * (zero-copy) and assembles the checkout quote.
 *
 * In a microservices world, this would require 6 HTTP calls totaling ~60ms.
 * In KubeFn, this reads 6 heap objects in ~0.001ms.
 */
@FnRoute(path = "/checkout/quote", methods = {"GET", "POST"})
@FnGroup("checkout-pipeline")
public class CheckoutQuoteFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Step 1: Execute the pipeline — all in-memory, zero-copy
        // Each function publishes its result to HeapExchange
        ctx.getFunction(AuthFunction.class).handle(request);
        ctx.getFunction(InventoryFunction.class).handle(request);
        ctx.getFunction(PricingFunction.class).handle(request);
        ctx.getFunction(ShippingFunction.class).handle(request);
        ctx.getFunction(TaxFunction.class).handle(request);
        ctx.getFunction(FraudCheckFunction.class).handle(request);

        // Step 2: Assemble quote from HeapExchange — all zero-copy reads
        var auth = ctx.heap().get("auth:user-001", Map.class).orElse(Map.of());
        var inventory = ctx.heap().get("inventory:PROD-42", Map.class).orElse(Map.of());
        var pricing = ctx.heap().get("pricing:current", Map.class).orElse(Map.of());
        var shipping = ctx.heap().get("shipping:estimate", Map.class).orElse(Map.of());
        var tax = ctx.heap().get("tax:calculated", Map.class).orElse(Map.of());
        var fraud = ctx.heap().get("fraud:result", Map.class).orElse(Map.of());

        long durationNanos = System.nanoTime() - startNanos;
        double durationMs = durationNanos / 1_000_000.0;

        // Assemble the full quote
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("auth", auth);
        quote.put("inventory", inventory);
        quote.put("pricing", pricing);
        quote.put("shipping", shipping);
        quote.put("tax", tax);
        quote.put("fraudCheck", fraud);
        quote.put("_meta", Map.of(
                "pipelineSteps", 7,
                "totalTimeMs", String.format("%.3f", durationMs),
                "totalTimeNanos", durationNanos,
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "7 functions composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(quote);
    }
}
