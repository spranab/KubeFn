# Fraud Detection

A fraud scoring function that reads pricing and auth context from the heap, computes a risk score based on multiple signals, and publishes the result for downstream consumers. This is the consumer pattern -- no HTTP calls to other services.

## Function Code

```java
@FnRoute(path = "/api/fraud/check", methods = {"POST"})
@FnGroup("risk")
@Consumes(keys = {"pricing:current", "auth:*"})
@Produces(keys = {"fraud:result"})
public class FraudDetector implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var body = request.bodyAs(FraudCheckRequest.class);

        // Read upstream data from heap (zero-copy, no HTTP)
        PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
        AuthContext auth = HeapReader.require(ctx, HeapKeys.authContext(body.userId()), AuthContext.class);

        // Compute risk score
        double score = 0.0;
        if (pricing.finalPrice() > 500) score += 0.3;
        if (auth.accountAgeDays() < 7) score += 0.4;
        if (body.shippingCountry().equals(body.billingCountry())) score -= 0.1;

        boolean approved = score < 0.7;
        var result = new FraudScore(score, approved,
            List.of(score > 0.5 ? "high-value-new-account" : "normal"));

        // Publish for downstream consumers
        ctx.heap().publish(HeapKeys.FRAUD_RESULT, result, FraudScore.class);
        return KubeFnResponse.ok(result);
    }
}
```

## HeapExchange Flow

1. `AuthFunction` publishes `auth:{userId}` (AuthContext) upstream
2. `PricingFunction` publishes `pricing:current` (PricingResult) upstream
3. `FraudDetector` reads both from the heap -- zero-copy, no network
4. `FraudDetector` publishes `fraud:result` (FraudScore) for downstream consumers (e.g., checkout orchestrator)

## Test and Deploy

```java
@Test
void highValueNewAccountFlagged() {
    var heap = new FakeHeapExchange();
    heap.publish(HeapKeys.PRICING_CURRENT,
        new PricingResult("USD", 600, 0, 600), PricingResult.class);
    heap.publish(HeapKeys.authContext("user-1"),
        new AuthContext("user-1", 3, List.of("buyer")), AuthContext.class);

    var ctx = FnContext.withHeap(heap);
    var fn = new FraudDetector();
    fn.setContext(ctx);

    fn.handle(KubeFnRequest.builder()
        .body(new FraudCheckRequest("user-1", "US", "US"))
        .build());

    FraudScore score = heap.get(HeapKeys.FRAUD_RESULT, FraudScore.class).get();
    // 0.3 (high value) + 0.4 (new account) - 0.1 (same country) = 0.6 < 0.7
    assertTrue(score.approved());
}
```

```bash
kubefn deploy --jar build/libs/fraud.jar --group risk
kubefn trace req-abc-123  # see heap reads/writes in the trace
```
