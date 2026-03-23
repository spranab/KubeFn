package com.kubefn.examples.usecases.queueworkers;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * DLQHandlerFunction — Replaces a dedicated dead-letter-queue processing container.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment consuming from the dead-letter queue
 *   - Often neglected — DLQ grows silently until disk fills or alerts fire
 *   - To reprocess, it makes the same network calls as the original consumer
 *   - Manual intervention usually requires kubectl exec or a separate admin tool
 *
 * AFTER (KubeFn):
 *   - One function handles the DLQ with concurrency=1 (careful reprocessing)
 *   - Reads the original failure reason from the message metadata
 *   - Attempts reprocessing with relaxed validation (e.g., skip inventory check)
 *   - Publishes failure audit log to heap for admin dashboards
 *   - Admin can query heap for failure patterns without kubectl access
 */
@FnQueue(topic = "orders-dlq", concurrency = 1)
@FnRoute(path = "/admin/dlq/status", methods = {"GET"})
@FnGroup("order-processing")
public class DLQHandlerFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(DLQHandlerFunction.class.getName());
    private static final int MAX_REPROCESSING_ATTEMPTS = 3;

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // If this is a GET request to the admin endpoint, return DLQ stats
        if ("GET".equals(request.method())) {
            return getDlqStatus();
        }

        // Otherwise, this is a message from the DLQ
        String body = request.bodyAsString();
        FailedMessage failed = parseFailedMessage(body);

        LOG.warning(String.format("DLQ processing: orderId=%s, attempt=%d, reason=%s",
                failed.orderId(), failed.attemptCount(), failed.failureReason()));

        // Track this failure in the audit log on the heap
        recordFailure(failed);

        // Attempt reprocessing with relaxed rules
        if (failed.attemptCount() < MAX_REPROCESSING_ATTEMPTS) {
            return attemptReprocessing(failed);
        }

        // Max retries exceeded — mark as permanently failed
        LOG.severe(String.format("Order %s permanently failed after %d attempts: %s",
                failed.orderId(), failed.attemptCount(), failed.failureReason()));

        var permanentFailure = new PermanentFailure(
                failed.orderId(), failed.failureReason(),
                failed.attemptCount(), Instant.now().toEpochMilli());
        ctx.heap().publish("dlq:permanent-failure:" + failed.orderId(), permanentFailure);

        return KubeFnResponse.ok(Map.of(
                "orderId", failed.orderId(),
                "action", "PERMANENTLY_FAILED",
                "attempts", failed.attemptCount()
        ));
    }

    private KubeFnResponse attemptReprocessing(FailedMessage failed) {
        // Relaxed validation — skip checks that may have caused the original failure
        boolean skipInventoryCheck = failed.failureReason().contains("inventory");
        boolean skipFraudCheck = failed.failureReason().contains("fraud");

        LOG.info(String.format("Reprocessing order %s (attempt %d), relaxed: inventory=%b, fraud=%b",
                failed.orderId(), failed.attemptCount() + 1, skipInventoryCheck, skipFraudCheck));

        // Simulate reprocessing — in production, call the order processing pipeline
        // with relaxed flags
        var reprocessed = Map.of(
                "orderId", failed.orderId(),
                "action", "REPROCESSED",
                "attempt", failed.attemptCount() + 1,
                "skipInventory", skipInventoryCheck,
                "skipFraud", skipFraudCheck
        );

        return KubeFnResponse.ok(reprocessed);
    }

    private void recordFailure(FailedMessage failed) {
        // Append to the DLQ audit log on the heap
        @SuppressWarnings("unchecked")
        List<DlqAuditEntry> auditLog = ctx.heap()
                .get("dlq:audit-log", List.class)
                .orElse(new ArrayList<>());

        List<DlqAuditEntry> updated = new ArrayList<>(auditLog);
        updated.add(new DlqAuditEntry(
                failed.orderId(), failed.failureReason(),
                failed.attemptCount(), Instant.now().toEpochMilli()));

        // Keep only last 100 entries to bound memory
        if (updated.size() > 100) {
            updated = updated.subList(updated.size() - 100, updated.size());
        }

        ctx.heap().publish("dlq:audit-log", updated);
    }

    private KubeFnResponse getDlqStatus() {
        @SuppressWarnings("unchecked")
        List<DlqAuditEntry> auditLog = ctx.heap()
                .get("dlq:audit-log", List.class)
                .orElse(List.of());

        return KubeFnResponse.ok(Map.of(
                "totalFailures", auditLog.size(),
                "recentFailures", auditLog.stream().skip(Math.max(0, auditLog.size() - 10)).toList()
        ));
    }

    private FailedMessage parseFailedMessage(String body) {
        return new FailedMessage("order-456", "inventory_check_timeout", 1, body);
    }

    public record FailedMessage(String orderId, String failureReason,
                                int attemptCount, String originalPayload) {}
    public record DlqAuditEntry(String orderId, String reason, int attempt, long timestamp) {}
    public record PermanentFailure(String orderId, String reason, int totalAttempts, long failedAt) {}
}
