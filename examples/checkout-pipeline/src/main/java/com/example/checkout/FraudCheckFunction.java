package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/fraud", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class FraudCheckFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Read auth + pricing + shipping from HeapExchange — all zero-copy
        var auth = ctx.heap().get("auth:user-001", Map.class).orElse(Map.of());
        var tax = ctx.heap().get("tax:calculated", Map.class).orElse(Map.of());

        double total = ((Number) tax.getOrDefault("total", 0.0)).doubleValue();
        String tier = (String) auth.getOrDefault("tier", "standard");

        double riskScore = "premium".equals(tier) ? 0.05 : 0.3;
        if (total > 500) riskScore += 0.2;

        Map<String, Object> fraud = Map.of(
                "riskScore", riskScore,
                "approved", riskScore < 0.7,
                "reason", riskScore < 0.7 ? "low_risk" : "manual_review"
        );

        ctx.heap().publish("fraud:result", fraud, Map.class);

        return KubeFnResponse.ok(fraud);
    }
}
