# Webhook Handler

Receive external webhooks (e.g., Stripe), validate signatures, publish the event data to the heap, and trigger downstream processing. The downstream function reads the webhook payload from the heap instead of making another API call.

## Function Code

```java
@FnRoute(path = "/webhooks/stripe", methods = {"POST"})
@FnGroup("integrations")
@Produces(keys = {"payment:*", "refund:*"})
public class StripeWebhookHandler implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String signature = request.header("Stripe-Signature");
        String secret = ctx.config().get("stripe.webhook.secret");
        if (!StripeSignature.verify(request.body(), signature, secret)) {
            return KubeFnResponse.badRequest("invalid signature");
        }

        var event = request.bodyAs(StripeEvent.class);

        switch (event.type()) {
            case "payment_intent.succeeded":
                var payment = event.dataAs(PaymentIntent.class);
                ctx.heap().publish(HeapKeys.paymentEvent(payment.id()), payment, PaymentIntent.class);
                ctx.getFunction(OrderFulfillment.class).handle(request);
                break;
            case "charge.refunded":
                var refund = event.dataAs(Refund.class);
                ctx.heap().publish(HeapKeys.refundEvent(refund.id()), refund, Refund.class);
                break;
        }

        return KubeFnResponse.ok("received");
    }
}
```

Downstream consumer:

```java
@FnRoute(path = "/internal/fulfill", methods = {"POST"})
@FnGroup("fulfillment")
@Consumes(keys = {"payment:*"})
public class OrderFulfillment implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var event = request.bodyAs(StripeEvent.class);
        PaymentIntent payment = ctx.heap()
            .get(HeapKeys.paymentEvent(event.dataAs(PaymentIntent.class).id()), PaymentIntent.class)
            .orElseThrow();

        // Fulfill using payment data from heap -- no Stripe API call needed
        return KubeFnResponse.ok("fulfilled");
    }
}
```

## HeapExchange Flow

1. Stripe sends POST to `/webhooks/stripe`
2. Handler validates signature, parses event, publishes to `payment:{id}` on heap
3. Handler calls `OrderFulfillment` via `ctx.getFunction()`
4. `OrderFulfillment` reads the payment object from the heap -- zero-copy, same JVM reference
5. No second API call to Stripe, no JSON re-parsing

## Test and Deploy

```java
@Test
void paymentSucceededTriggersFullfillment() {
    var heap = new FakeHeapExchange();
    var ctx = FnContext.withHeap(heap);
    var fn = new StripeWebhookHandler();
    fn.setContext(ctx);

    var request = KubeFnRequest.builder()
        .header("Stripe-Signature", validSignature)
        .body(stripePaymentEvent)
        .build();

    fn.handle(request);

    assertTrue(heap.contains("payment:pi_123"));
}
```

```bash
kubefn deploy --jar build/libs/integrations.jar --group integrations
# Configure Stripe webhook URL to point at your ingress:
# https://api.example.com/webhooks/stripe
```
