package com.kubefn.examples.usecases.webhooks;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * StripeWebhookFunction — Replaces a dedicated webhook-receiver Deployment for Stripe.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment (2+ replicas for HA) just to receive Stripe webhooks
 *   - Needs its own ingress, TLS, service, HPA configuration
 *   - On receiving a webhook, makes network calls to order-service, notification-service
 *   - Each webhook event triggers a cascade of HTTP calls across the service mesh
 *   - Webhook signature verification requires Stripe SDK — each container bundles it
 *
 * AFTER (KubeFn):
 *   - One function handles all Stripe webhook types on a single route
 *   - Payment status published to heap — order functions read it zero-copy
 *   - No network calls to propagate payment state to other functions
 *   - Signature verification happens once, result is shared via heap
 */
@FnRoute(path = "/webhooks/stripe", methods = {"POST"})
@FnGroup("payments")
public class StripeWebhookFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(StripeWebhookFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String payload = request.bodyAsString();
        String signature = request.queryParam("stripe-signature").orElse("");

        // 1. Verify webhook signature (simulated — real impl uses Stripe SDK)
        if (!verifySignature(payload, signature)) {
            LOG.warning("Stripe webhook signature verification failed");
            return KubeFnResponse.status(401).body(Map.of("error", "invalid_signature"));
        }

        // 2. Parse the event (simplified — real impl parses JSON)
        StripeEvent event = parseEvent(payload);
        LOG.info(String.format("Stripe webhook: type=%s, id=%s", event.type(), event.id()));

        // 3. Route by event type and publish state to heap
        switch (event.type()) {
            case "payment_intent.succeeded" -> handlePaymentSucceeded(event);
            case "payment_intent.payment_failed" -> handlePaymentFailed(event);
            case "charge.refunded" -> handleRefund(event);
            case "customer.subscription.created" -> handleSubscriptionCreated(event);
            case "customer.subscription.deleted" -> handleSubscriptionCanceled(event);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(event);
            default -> LOG.info("Unhandled Stripe event type: " + event.type());
        }

        // 4. Always publish the raw event for audit trail
        ctx.heap().publish("stripe:event:" + event.id(), event);

        // Stripe expects 200 OK to acknowledge receipt
        return KubeFnResponse.ok(Map.of("received", true));
    }

    private void handlePaymentSucceeded(StripeEvent event) {
        var status = new PaymentStatus(event.resourceId(), "SUCCEEDED",
                event.amount(), event.currency(), Instant.now().toEpochMilli());
        ctx.heap().publish("payment:" + event.resourceId(), status);
        LOG.info("Payment succeeded: " + event.resourceId() + " $" + event.amount() / 100.0);
    }

    private void handlePaymentFailed(StripeEvent event) {
        var status = new PaymentStatus(event.resourceId(), "FAILED",
                event.amount(), event.currency(), Instant.now().toEpochMilli());
        ctx.heap().publish("payment:" + event.resourceId(), status);
        LOG.warning("Payment failed: " + event.resourceId());
    }

    private void handleRefund(StripeEvent event) {
        var status = new PaymentStatus(event.resourceId(), "REFUNDED",
                event.amount(), event.currency(), Instant.now().toEpochMilli());
        ctx.heap().publish("payment:" + event.resourceId(), status);
        LOG.info("Refund processed: " + event.resourceId());
    }

    private void handleSubscriptionCreated(StripeEvent event) {
        ctx.heap().publish("subscription:" + event.resourceId(),
                new SubscriptionStatus(event.resourceId(), "ACTIVE", Instant.now().toEpochMilli()));
    }

    private void handleSubscriptionCanceled(StripeEvent event) {
        ctx.heap().publish("subscription:" + event.resourceId(),
                new SubscriptionStatus(event.resourceId(), "CANCELED", Instant.now().toEpochMilli()));
    }

    private void handleInvoicePaymentFailed(StripeEvent event) {
        ctx.heap().publish("invoice-failure:" + event.resourceId(),
                new InvoiceFailure(event.resourceId(), "PAYMENT_FAILED", Instant.now().toEpochMilli()));
    }

    private boolean verifySignature(String payload, String signature) {
        // Simulated signature verification — real impl uses Stripe's webhook secret
        return payload != null && !payload.isEmpty();
    }

    private StripeEvent parseEvent(String payload) {
        return new StripeEvent("evt_12345", "payment_intent.succeeded",
                "pi_67890", 4999, "usd", Instant.now().toEpochMilli());
    }

    public record StripeEvent(String id, String type, String resourceId,
                              long amount, String currency, long timestamp) {}
    public record PaymentStatus(String paymentId, String status, long amount,
                                String currency, long updatedAt) {}
    public record SubscriptionStatus(String subscriptionId, String status, long updatedAt) {}
    public record InvoiceFailure(String invoiceId, String reason, long failedAt) {}
}
