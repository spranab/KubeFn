package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/pricing", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class PricingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Read auth context zero-copy from HeapExchange
        var auth = ctx.heap().get("auth:user-001", Map.class).orElse(Map.of());
        String tier = (String) auth.getOrDefault("tier", "standard");

        double basePrice = 99.99;
        double discount = "premium".equals(tier) ? 0.15 : 0.0;

        Map<String, Object> pricing = Map.of(
                "basePrice", basePrice,
                "discount", discount,
                "finalPrice", basePrice * (1 - discount),
                "currency", "USD"
        );

        ctx.heap().publish("pricing:current", pricing, Map.class);

        return KubeFnResponse.ok(pricing);
    }
}
