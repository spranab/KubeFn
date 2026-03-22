package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/tax", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class TaxFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var pricing = ctx.heap().get("pricing:current", Map.class).orElse(Map.of());
        double finalPrice = ((Number) pricing.getOrDefault("finalPrice", 0.0)).doubleValue();

        double taxRate = 0.0825; // Texas
        Map<String, Object> tax = Map.of(
                "subtotal", finalPrice,
                "taxRate", taxRate,
                "taxAmount", finalPrice * taxRate,
                "total", finalPrice * (1 + taxRate)
        );

        ctx.heap().publish("tax:calculated", tax, Map.class);

        return KubeFnResponse.ok(tax);
    }
}
