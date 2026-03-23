package com.kubefn.examples.usecases.queueworkers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * NotificationWorkerFunction — Replaces a dedicated notification-sending Deployment.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment (2-3 replicas) consuming notification events from a queue
 *   - Makes HTTP calls to user-profile-service to get notification preferences
 *   - Makes HTTP calls to email-service, SMS-service, push-service separately
 *   - Each notification = 3-4 network round-trips across service mesh
 *   - Batching requires complex consumer group coordination across replicas
 *
 * AFTER (KubeFn):
 *   - One function with batchSize=10 — the runtime batches for you
 *   - User preferences are on the heap (published by auth/profile functions)
 *   - No network call to look up preferences — zero-copy heap read
 *   - Publishes notification receipts to heap for audit trail
 */
@FnQueue(topic = "notifications", concurrency = 2, batchSize = 10,
         deadLetterTopics = {"notifications-dlq"})
@FnGroup("communications")
public class NotificationWorkerFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(NotificationWorkerFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // The runtime delivers batched messages as a JSON array in the body
        String body = request.bodyAsString();
        List<NotificationEvent> batch = parseBatch(body);

        int sent = 0;
        int skipped = 0;
        List<NotificationReceipt> receipts = new ArrayList<>();

        for (NotificationEvent event : batch) {
            // Read user preferences from heap — published by the user profile function
            Optional<UserPreferences> prefs = ctx.heap()
                    .get("user-prefs:" + event.userId(), UserPreferences.class);

            if (prefs.isEmpty()) {
                // No preferences found — use defaults (email only)
                prefs = Optional.of(new UserPreferences(event.userId(), true, false, false));
            }

            UserPreferences userPrefs = prefs.get();

            // Route to appropriate channel based on preferences
            boolean delivered = false;

            if (userPrefs.emailEnabled() && event.channel().equals("email")) {
                sendEmail(event);
                delivered = true;
            } else if (userPrefs.smsEnabled() && event.channel().equals("sms")) {
                sendSms(event);
                delivered = true;
            } else if (userPrefs.pushEnabled() && event.channel().equals("push")) {
                sendPush(event);
                delivered = true;
            }

            if (delivered) {
                sent++;
                receipts.add(new NotificationReceipt(
                        event.notificationId(), event.userId(), event.channel(),
                        "DELIVERED", Instant.now().toEpochMilli()));
            } else {
                skipped++;
                receipts.add(new NotificationReceipt(
                        event.notificationId(), event.userId(), event.channel(),
                        "SKIPPED_BY_PREFERENCE", Instant.now().toEpochMilli()));
            }
        }

        // Publish batch receipts to heap for audit/analytics functions
        ctx.heap().publish("notifications:last-batch-receipts", receipts);

        LOG.info(String.format("Notification batch: %d sent, %d skipped (of %d)",
                sent, skipped, batch.size()));

        return KubeFnResponse.ok(Map.of("sent", sent, "skipped", skipped, "batchSize", batch.size()));
    }

    // --- Simulated sending (real impl calls SMTP/Twilio/FCM) ---

    private void sendEmail(NotificationEvent event) {
        LOG.fine("EMAIL -> " + event.userId() + ": " + event.message());
    }

    private void sendSms(NotificationEvent event) {
        LOG.fine("SMS -> " + event.userId() + ": " + event.message());
    }

    private void sendPush(NotificationEvent event) {
        LOG.fine("PUSH -> " + event.userId() + ": " + event.message());
    }

    private List<NotificationEvent> parseBatch(String body) {
        // Simplified — real impl parses JSON array
        return List.of(
                new NotificationEvent(UUID.randomUUID().toString(), "user-101", "email",
                        "Your order has shipped!", "order-update"),
                new NotificationEvent(UUID.randomUUID().toString(), "user-102", "sms",
                        "Payment received: $49.99", "payment-confirmation")
        );
    }

    public record NotificationEvent(String notificationId, String userId, String channel,
                                    String message, String type) {}
    public record UserPreferences(String userId, boolean emailEnabled,
                                  boolean smsEnabled, boolean pushEnabled) {}
    public record NotificationReceipt(String notificationId, String userId, String channel,
                                      String status, long timestamp) {}
}
