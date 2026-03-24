# Checkout Pipeline

A 4-step e-commerce checkout pipeline where each function publishes its result to the heap. The orchestrator calls all steps in sequence and assembles the final response from heap data -- zero HTTP calls between steps.

## Function Code

```java
@FnRoute(path = "/api/checkout", methods = {"POST"})
@FnGroup("checkout")
@Consumes(keys = {"pricing:current", "tax:calculated", "inventory:*", "fraud:result"})
public class CheckoutOrchestrator implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Execute pipeline -- each step publishes to heap
        var result = PipelineBuilder.create(ctx, request)
            .step("pricing", PricingFunction.class)
            .step("tax", TaxFunction.class)
            .step("inventory", InventoryFunction.class)
            .step("fraud", FraudFunction.class)
            .execute();

        // Read results from heap (zero-copy)
        PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
        TaxCalculation tax = HeapReader.require(ctx, HeapKeys.TAX_CALCULATED, TaxCalculation.class);
        FraudScore fraud = HeapReader.require(ctx, HeapKeys.FRAUD_RESULT, FraudScore.class);

        if (!fraud.approved()) {
            return KubeFnResponse.badRequest(Map.of("error", "order flagged for review"));
        }

        return KubeFnResponse.ok(Map.of(
            "total", tax.total(), "currency", pricing.currency(), "approved", true));
    }
}
```

Producer (pricing step):

```java
@FnRoute(path = "/api/pricing", methods = {"POST"})
@FnGroup("checkout")
@Produces(keys = {"pricing:current"})
public class PricingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var body = request.bodyAs(CheckoutRequest.class);
        double discount = body.quantity() > 10 ? 0.15 : 0.0;
        double finalPrice = body.unitPrice() * body.quantity() * (1 - discount);
        var result = new PricingResult("USD", body.unitPrice(), discount, finalPrice);
        ctx.heap().publish(HeapKeys.PRICING_CURRENT, result, PricingResult.class);
        return KubeFnResponse.ok(result);
    }
}
```

## HeapExchange Flow

1. `PricingFunction` publishes `pricing:current` (PricingResult)
2. `TaxFunction` reads `pricing:current`, publishes `tax:calculated` (TaxCalculation)
3. `InventoryFunction` publishes `inventory:{sku}` (InventoryStatus)
4. `FraudFunction` reads `pricing:current` + `auth:{userId}`, publishes `fraud:result` (FraudScore)
5. `CheckoutOrchestrator` reads all four results from the heap and assembles the response

Four functions, four heap interactions, one HTTP round-trip. Total latency: ~5.7ms.

## Test and Deploy

```java
@Test
void checkoutRejectsFraud() {
    var heap = new FakeHeapExchange();
    var ctx = FnContext.withHeap(heap);

    // Pre-populate heap with upstream results
    heap.publish(HeapKeys.PRICING_CURRENT, new PricingResult("USD", 99, 0, 99), PricingResult.class);
    heap.publish(HeapKeys.TAX_CALCULATED, new TaxCalculation(99, 0.08, 7.92, 106.92), TaxCalculation.class);
    heap.publish(HeapKeys.FRAUD_RESULT, new FraudScore(0.9, false, List.of("high-risk")), FraudScore.class);

    // Orchestrator reads from heap and rejects
    var fn = new CheckoutOrchestrator();
    fn.setContext(ctx);
    var response = fn.handle(KubeFnRequest.empty());
    assertEquals(400, response.statusCode());
}
```

```bash
kubefn deploy --jar build/libs/checkout.jar --group checkout
curl -X POST http://localhost:8080/api/checkout -d '{"sku":"SKU-1","quantity":2,"unitPrice":49.99}'
```
