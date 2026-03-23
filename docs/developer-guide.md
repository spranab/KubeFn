# KubeFn Developer Guide — Independent Function Development

## The Problem

In KubeFn, functions share objects via HeapExchange. But how does Developer B know what Developer A published? How do you develop independently?

## The Solution: Contracts Module

KubeFn uses a **shared contracts module** — the equivalent of Protobuf definitions in microservices. Instead of defining wire formats, you define heap object shapes.

```
kubefn-contracts/       ← shared types (records)
  AuthContext.java      ← what auth functions publish
  PricingResult.java    ← what pricing functions publish
  FraudScore.java       ← what fraud functions publish
  HeapKeys.java         ← central key registry

my-new-function/        ← your function (developed independently)
  build.gradle.kts
    compileOnly("com.kubefn:kubefn-api:0.3.1")
    compileOnly("com.kubefn:kubefn-contracts:0.3.1")
```

## Developer Workflow

### 1. Check what's available

Look at `kubefn-contracts` to see what heap objects exist:

```java
// HeapKeys.java shows all registered keys:
HeapKeys.PRICING_CURRENT    // "pricing:current" → PricingResult
HeapKeys.FRAUD_RESULT       // "fraud:result" → FraudScore
HeapKeys.auth("user-001")   // "auth:user-001" → AuthContext
```

### 2. Write your function (independently)

```java
@FnRoute(path = "/tax/calculate", methods = {"POST"})
@FnGroup("tax-service")
public class TaxFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) {
        // Read pricing from HeapExchange — typed, zero-copy
        PricingResult pricing = ctx.heap()
            .get(HeapKeys.PRICING_CURRENT, PricingResult.class)
            .orElseThrow(() -> new RuntimeException("Pricing not on heap"));

        // Use the typed object — full IDE autocomplete
        double tax = pricing.finalPrice() * 0.0825;

        // Publish your result (also a contract type)
        var result = new TaxCalculation(
            pricing.finalPrice(), 0.0825, tax, pricing.finalPrice() + tax);
        ctx.heap().publish(HeapKeys.TAX_CALCULATED, result, TaxCalculation.class);

        return KubeFnResponse.ok(result);
    }
}
```

### 3. Build and deploy independently

```bash
# Build just your function
gradle jar

# Deploy to the running organism
kubefn deploy build/libs/tax-function.jar tax-service
```

Your function joins the organism. It reads `PricingResult` from the heap (published by a function you never touched) and publishes `TaxCalculation` for other functions to consume.

### 4. Add new contract types

When you need a new shared type:

```java
// In kubefn-contracts/
public record TaxCalculation(
    double subtotal,
    double taxRate,
    double taxAmount,
    double total
) {
    public static String heapKey() { return "tax:calculated"; }
}
```

Register the key in `HeapKeys.java` and bump the contracts version.

## Comparison to Microservices

| Aspect | Microservices | KubeFn |
|---|---|---|
| Contract format | Protobuf / OpenAPI | Java records in contracts module |
| Where contracts live | `.proto` files / swagger specs | `kubefn-contracts` module |
| How to discover APIs | Swagger UI / service catalog | `HeapKeys.java` + IDE autocomplete |
| Runtime coupling | HTTP + serialization | Zero-copy heap reference |
| Independent deploy? | Yes (separate containers) | Yes (separate JARs) |
| Independent develop? | Yes (compile against .proto) | Yes (compile against contracts) |

## Key Principle

> **Contracts define the shape. HeapExchange shares the object. Functions develop independently.**

Each developer only needs:
1. `kubefn-api` (the function interface)
2. `kubefn-contracts` (the shared types)
3. Their own function code

They never need the source code of other functions.
