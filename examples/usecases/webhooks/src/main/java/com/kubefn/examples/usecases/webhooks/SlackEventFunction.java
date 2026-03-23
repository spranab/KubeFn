package com.kubefn.examples.usecases.webhooks;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SlackEventFunction — Replaces a dedicated Slack bot/event handler Deployment.
 *
 * BEFORE (traditional K8s):
 *   - A separate Deployment running a Slack bot framework (Bolt, etc.)
 *   - Needs its own ingress for Slack's HTTP delivery
 *   - Makes HTTP calls to internal services when slash commands are invoked
 *   - Separate container image, CI pipeline, resource allocation
 *   - URL verification challenge requires the container to be always running
 *
 * AFTER (KubeFn):
 *   - One function handles all Slack events on a single route
 *   - Slash commands read data directly from heap (e.g., /status reads health data)
 *   - Event data published to heap for other functions to react to
 *   - No dedicated container — the function is always warm in the organism
 */
@FnRoute(path = "/webhooks/slack", methods = {"POST"})
@FnGroup("developer-tools")
public class SlackEventFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(SlackEventFunction.class.getName());

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();

        // 1. Handle URL verification challenge (Slack sends this during setup)
        if (body.contains("url_verification")) {
            String challenge = extractChallenge(body);
            return KubeFnResponse.ok(Map.of("challenge", challenge));
        }

        // 2. Parse the event
        SlackEvent event = parseEvent(body);
        LOG.info(String.format("Slack event: type=%s, user=%s", event.type(), event.userId()));

        // 3. Route by event type
        switch (event.type()) {
            case "message" -> handleMessage(event);
            case "reaction_added" -> handleReaction(event);
            case "slash_command" -> handleSlashCommand(event);
            case "app_mention" -> handleAppMention(event);
            default -> LOG.info("Unhandled Slack event: " + event.type());
        }

        // Acknowledge receipt — Slack requires 200 within 3 seconds
        return KubeFnResponse.ok(Map.of("ok", true));
    }

    private void handleMessage(SlackEvent event) {
        // Publish message to heap so analytics/search functions can index it
        ctx.heap().publish("slack:message:latest", new SlackMessage(
                event.channelId(), event.userId(), event.text(), event.timestamp()));

        LOG.fine(String.format("Message in %s from %s: %s",
                event.channelId(), event.userId(), event.text()));
    }

    private void handleReaction(SlackEvent event) {
        // Track reactions for engagement metrics — other functions can read this
        ctx.heap().publish("slack:reaction:latest", Map.of(
                "emoji", event.text(),
                "userId", event.userId(),
                "channelId", event.channelId(),
                "timestamp", event.timestamp()
        ));
    }

    private void handleSlashCommand(SlackEvent event) {
        String command = event.text();

        if (command.startsWith("/status")) {
            // Read system health directly from heap — published by HealthMonitorFunction
            var health = ctx.heap().get("deps:health", Map.class).orElse(Map.of());
            var metrics = ctx.heap().get("ops:metrics-summary", Map.class).orElse(Map.of());

            // Publish response for the Slack response function to pick up
            ctx.heap().publish("slack:response:" + event.userId(), new SlackResponse(
                    event.channelId(), formatStatusResponse(health, metrics),
                    Instant.now().toEpochMilli()));

        } else if (command.startsWith("/deploy")) {
            // Trigger a deploy by publishing to heap — deploy functions pick it up
            ctx.heap().publish("deploy:trigger:latest", Map.of(
                    "requestedBy", event.userId(),
                    "command", command,
                    "timestamp", Instant.now().toEpochMilli()
            ));

            ctx.heap().publish("slack:response:" + event.userId(), new SlackResponse(
                    event.channelId(), "Deploy triggered. Check #deployments for progress.",
                    Instant.now().toEpochMilli()));
        }
    }

    private void handleAppMention(SlackEvent event) {
        // Bot was mentioned — publish for NLP/response functions to handle
        ctx.heap().publish("slack:mention:latest", new SlackMessage(
                event.channelId(), event.userId(), event.text(), event.timestamp()));
    }

    private String formatStatusResponse(Map<?, ?> health, Map<?, ?> metrics) {
        return String.format("System Status: %d deps monitored, %s",
                health.size(), health.isEmpty() ? "no data" : "all checks loaded");
    }

    private String extractChallenge(String body) {
        // Simplified — real impl parses JSON
        return "challenge_token_12345";
    }

    private SlackEvent parseEvent(String body) {
        return new SlackEvent("message", "U12345", "C67890",
                "Hello from Slack!", Instant.now().toEpochMilli());
    }

    public record SlackEvent(String type, String userId, String channelId,
                             String text, long timestamp) {}
    public record SlackMessage(String channelId, String userId, String text, long timestamp) {}
    public record SlackResponse(String channelId, String text, long timestamp) {}
}
