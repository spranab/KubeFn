package com.example.checkout;

import io.kubefn.api.*;

import java.util.Map;

@FnRoute(path = "/auth", methods = {"POST"})
@FnGroup("checkout-pipeline")
public class AuthFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Simulate auth token validation (~0.1ms in-memory vs ~15ms over network)
        String userId = request.queryParam("userId").orElse("user-001");
        Map<String, Object> authContext = Map.of(
                "userId", userId,
                "authenticated", true,
                "tier", "premium",
                "timestamp", System.nanoTime()
        );

        // Publish to HeapExchange — ALL downstream functions read this zero-copy
        ctx.heap().publish("auth:" + userId, authContext, Map.class);

        return KubeFnResponse.ok(authContext);
    }
}
