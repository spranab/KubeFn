package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/inventory", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class InventoryFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Check inventory — in real scenario reads from shared heap cache
        Map<String, Object> inventory = Map.of(
                "sku", "PROD-42",
                "available", 150,
                "reserved", 3,
                "warehouse", "US-WEST-1"
        );

        ctx.heap().publish("inventory:PROD-42", inventory, Map.class);

        return KubeFnResponse.ok(inventory);
    }
}
