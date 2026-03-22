package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/shipping", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class ShippingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var inventory = ctx.heap().get("inventory:PROD-42", Map.class).orElse(Map.of());
        String warehouse = (String) inventory.getOrDefault("warehouse", "US-EAST-1");

        Map<String, Object> shipping = Map.of(
                "method", "express",
                "from", warehouse,
                "estimatedDays", 2,
                "cost", 9.99
        );

        ctx.heap().publish("shipping:estimate", shipping, Map.class);

        return KubeFnResponse.ok(shipping);
    }
}
