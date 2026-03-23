# KubeFn — AI Agent Instructions

## When generating KubeFn functions, ALWAYS:

1. Annotate with `@FnRoute(path, methods)` and `@FnGroup(name)`
2. Implement `KubeFnHandler` and `FnContextAware`
3. Use contract types from `com.kubefn.contracts.*`
4. Use `HeapKeys` constants for heap key names
5. Handle `Optional.empty()` from `heap.get()` with graceful fallback
6. Never serialize heap objects — they are live references
7. Return `KubeFnResponse.ok(body)` with a Map or contract type

## Template
```java
package com.example;

import com.kubefn.api.*;
import com.kubefn.contracts.*;

@FnRoute(path = "/path", methods = {"GET", "POST"})
@FnGroup("group-name")
public class MyFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Read from heap (zero-copy)
        PricingResult pricing = ctx.heap()
            .get(HeapKeys.PRICING_CURRENT, PricingResult.class)
            .orElseThrow();

        // Business logic
        var result = new TaxCalculation(
            pricing.finalPrice(), 0.0825,
            pricing.finalPrice() * 0.0825,
            pricing.finalPrice() * 1.0825);

        // Publish to heap (zero-copy)
        ctx.heap().publish(HeapKeys.TAX_CALCULATED, result, TaxCalculation.class);

        return KubeFnResponse.ok(result);
    }
}
```

## Anti-patterns
- `ctx.heap().get("some-key", Map.class)` → use contract types, not Map
- `new ObjectMapper().writeValueAsString(heapObject)` → NEVER serialize heap objects
- `String key = "pricing:" + id` → use HeapKeys constants
- `heap.get(...).get()` → always handle Optional, never call .get() directly
