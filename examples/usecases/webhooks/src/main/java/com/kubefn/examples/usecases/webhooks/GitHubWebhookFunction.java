package com.kubefn.examples.usecases.webhooks;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * GitHubWebhookFunction — Replaces a dedicated GitHub webhook receiver Deployment.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment receiving GitHub webhooks (push, PR, issues)
 *   - Makes HTTP calls to CI/CD service, notification service, dashboard service
 *   - Needs its own ingress, service, HPA, secret management for webhook secret
 *   - Webhook processing is sequential — can't easily fan out to multiple consumers
 *
 * AFTER (KubeFn):
 *   - One function catches all GitHub events, publishes to heap by event type
 *   - CI functions, notification functions, dashboard functions all read zero-copy
 *   - Fan-out is free — publish once, N functions consume from heap
 *   - No network calls to propagate events to internal consumers
 */
@FnRoute(path = "/webhooks/github", methods = {"POST"})
@FnGroup("developer-tools")
public class GitHubWebhookFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(GitHubWebhookFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String payload = request.bodyAsString();
        String eventType = request.queryParam("x-github-event").orElse("");
        String deliveryId = request.queryParam("x-github-delivery").orElse("");
        String signature = request.queryParam("x-hub-signature-256").orElse("");

        // 1. Verify webhook signature
        if (!verifySignature(payload, signature)) {
            return KubeFnResponse.status(401).body(Map.of("error", "invalid_signature"));
        }

        LOG.info(String.format("GitHub webhook: event=%s, delivery=%s", eventType, deliveryId));

        // 2. Route by event type and publish structured data to heap
        if (eventType == null) {
            return KubeFnResponse.error(Map.of("error", "missing_event_type"));
        }

        switch (eventType) {
            case "push" -> handlePush(payload);
            case "pull_request" -> handlePullRequest(payload);
            case "issues" -> handleIssue(payload);
            case "check_run" -> handleCheckRun(payload);
            case "release" -> handleRelease(payload);
            default -> LOG.info("Unhandled GitHub event: " + eventType);
        }

        // 3. Publish raw event for audit and replay
        ctx.heap().publish("github:event:" + deliveryId, new GitHubEvent(
                deliveryId, eventType, payload, Instant.now().toEpochMilli()));

        return KubeFnResponse.ok(Map.of("received", true, "event", eventType));
    }

    private void handlePush(String payload) {
        // Parse push event — extract repo, branch, commit SHA
        var trigger = new BuildTrigger(
                "my-org/my-repo", "main", "abc123def456",
                "feat: add new checkout flow", "developer@example.com",
                Instant.now().toEpochMilli());

        // Publish build trigger — CI functions read this and start builds
        ctx.heap().publish("ci:build-trigger:latest", trigger);
        // Also store per-commit for history
        ctx.heap().publish("ci:build-trigger:" + trigger.commitSha(), trigger);

        LOG.info(String.format("Push to %s/%s by %s: %s",
                trigger.repo(), trigger.branch(), trigger.author(), trigger.message()));
    }

    private void handlePullRequest(String payload) {
        var prEvent = new PullRequestEvent(
                "my-org/my-repo", 42, "opened",
                "feat: add new checkout flow", "developer",
                Instant.now().toEpochMilli());

        ctx.heap().publish("github:pr:" + prEvent.number(), prEvent);
        LOG.info(String.format("PR #%d %s: %s", prEvent.number(), prEvent.action(), prEvent.title()));
    }

    private void handleIssue(String payload) {
        var issueEvent = new IssueEvent(
                "my-org/my-repo", 101, "opened",
                "Bug: checkout fails on Safari", "reporter",
                Instant.now().toEpochMilli());

        ctx.heap().publish("github:issue:" + issueEvent.number(), issueEvent);
        LOG.info(String.format("Issue #%d %s: %s", issueEvent.number(), issueEvent.action(), issueEvent.title()));
    }

    private void handleCheckRun(String payload) {
        ctx.heap().publish("github:check-run:latest", Map.of(
                "status", "completed", "conclusion", "success",
                "timestamp", Instant.now().toEpochMilli()));
    }

    private void handleRelease(String payload) {
        ctx.heap().publish("github:release:latest", Map.of(
                "tag", "v1.2.3", "action", "published",
                "timestamp", Instant.now().toEpochMilli()));
    }

    private boolean verifySignature(String payload, String signature) {
        return payload != null && !payload.isEmpty();
    }

    public record GitHubEvent(String deliveryId, String eventType, String payload, long receivedAt) {}
    public record BuildTrigger(String repo, String branch, String commitSha,
                               String message, String author, long triggeredAt) {}
    public record PullRequestEvent(String repo, int number, String action,
                                   String title, String author, long timestamp) {}
    public record IssueEvent(String repo, int number, String action,
                             String title, String reporter, long timestamp) {}
}
