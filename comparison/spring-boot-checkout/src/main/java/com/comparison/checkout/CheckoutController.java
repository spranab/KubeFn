package com.comparison.checkout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checkout orchestrator and individual "microservice" endpoints.
 *
 * <p>In a real microservices deployment, each endpoint below would be a
 * separate service on a separate host. Here they live in one process to
 * keep the comparison simple — which actually <em>favors</em> Spring Boot
 * since there's no real network latency, only localhost loopback.
 *
 * <p>The orchestrator ({@code POST /checkout}) calls each service via
 * {@link RestTemplate}, which means every step involves:
 * <ol>
 *   <li>HTTP request construction</li>
 *   <li>TCP connection (localhost)</li>
 *   <li>JSON serialization of the response body</li>
 *   <li>HTTP response parsing</li>
 *   <li>JSON deserialization into a Map</li>
 * </ol>
 *
 * <p>This is the EXACT same business logic as KubeFn's checkout pipeline.
 * The only difference is architecture.
 */
@RestController
public class CheckoutController {

    private static final String BASE_URL = "http://localhost:9090";

    @Autowired
    private RestTemplate restTemplate;

    // ── Orchestrator: calls all 7 services via HTTP ─────────────────────

    /**
     * POST /checkout — the microservices orchestrator.
     * Calls 7 downstream services sequentially via HTTP, deserializes each
     * response, and assembles the final checkout result.
     */
    @PostMapping("/checkout")
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkout(@RequestBody(required = false) Map<String, Object> body) {
        long pipelineStart = System.nanoTime();
        Map<String, Object> stepTimings = new LinkedHashMap<>();

        // Step 1: Auth — HTTP GET to auth service
        long stepStart = System.nanoTime();
        Map<String, Object> auth = restTemplate.getForObject(
                BASE_URL + "/auth/verify?userId=user-001", Map.class);
        stepTimings.put("auth", stepTiming(stepStart));

        // Step 2: Inventory — HTTP GET to inventory service
        stepStart = System.nanoTime();
        Map<String, Object> inventory = restTemplate.getForObject(
                BASE_URL + "/inventory/check?sku=PROD-42", Map.class);
        stepTimings.put("inventory", stepTiming(stepStart));

        // Step 3: Pricing — HTTP GET to pricing service
        stepStart = System.nanoTime();
        Map<String, Object> pricing = restTemplate.getForObject(
                BASE_URL + "/pricing/calculate?basePrice=99.99&quantity=2", Map.class);
        stepTimings.put("pricing", stepTiming(stepStart));

        // Step 4: Shipping — HTTP GET to shipping service
        stepStart = System.nanoTime();
        Map<String, Object> shipping = restTemplate.getForObject(
                BASE_URL + "/shipping/estimate?weight=2.5&zone=US-WEST", Map.class);
        stepTimings.put("shipping", stepTiming(stepStart));

        // Step 5: Tax — HTTP GET to tax service
        double subtotal = 169.98; // 2 * 84.99 after discount
        stepStart = System.nanoTime();
        Map<String, Object> tax = restTemplate.getForObject(
                BASE_URL + "/tax/calculate?subtotal=" + subtotal + "&state=CA", Map.class);
        stepTimings.put("tax", stepTiming(stepStart));

        // Step 6: Fraud — HTTP GET to fraud service
        stepStart = System.nanoTime();
        Map<String, Object> fraud = restTemplate.getForObject(
                BASE_URL + "/fraud/check?userId=user-001&amount=" + subtotal, Map.class);
        stepTimings.put("fraud", stepTiming(stepStart));

        // Step 7: Assembly — HTTP POST to assembly service
        stepStart = System.nanoTime();
        Map<String, Object> assemblyRequest = new LinkedHashMap<>();
        assemblyRequest.put("auth", auth);
        assemblyRequest.put("inventory", inventory);
        assemblyRequest.put("pricing", pricing);
        assemblyRequest.put("shipping", shipping);
        assemblyRequest.put("tax", tax);
        assemblyRequest.put("fraud", fraud);
        Map<String, Object> assembled = restTemplate.postForObject(
                BASE_URL + "/checkout/assemble", assemblyRequest, Map.class);
        stepTimings.put("assembly", stepTiming(stepStart));

        long pipelineEnd = System.nanoTime();
        double totalMs = (pipelineEnd - pipelineStart) / 1_000_000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", assembled);
        result.put("_meta", Map.of(
                "architecture", "microservices (HTTP + JSON)",
                "pipelineSteps", 7,
                "totalTimeMs", String.format("%.3f", totalMs),
                "httpCalls", 7,
                "serializationRoundTrips", 14,
                "steps", stepTimings,
                "note", "7 HTTP round-trips with JSON serialize/deserialize each. " +
                        "Compare with KubeFn: same logic, 0 HTTP calls, 0 serialization."
        ));

        return result;
    }

    // ── Individual "microservice" endpoints ──────────────────────────────

    /**
     * Auth service — verifies user identity and returns auth context.
     */
    @GetMapping("/auth/verify")
    public Map<String, Object> authVerify(@RequestParam(defaultValue = "user-001") String userId) {
        // Simulate auth lookup — same logic as KubeFn's AuthFunction
        return Map.of(
                "userId", userId,
                "email", userId + "@example.com",
                "roles", List.of("customer", "premium"),
                "permissions", List.of("checkout", "view_orders", "apply_discount"),
                "authenticated", true,
                "authMethod", "JWT",
                "tokenExpiry", Instant.now().plusSeconds(3600).toString()
        );
    }

    /**
     * Inventory service — checks stock levels for a SKU.
     */
    @GetMapping("/inventory/check")
    public Map<String, Object> inventoryCheck(@RequestParam(defaultValue = "PROD-42") String sku) {
        // Simulate inventory check — same logic as KubeFn's InventoryFunction
        return Map.of(
                "sku", sku,
                "available", 142,
                "reserved", 8,
                "warehouse", "US-WEST-2",
                "reorderPoint", 50,
                "inStock", true
        );
    }

    /**
     * Pricing service — calculates pricing with discounts.
     */
    @GetMapping("/pricing/calculate")
    public Map<String, Object> pricingCalculate(
            @RequestParam(defaultValue = "99.99") double basePrice,
            @RequestParam(defaultValue = "2") int quantity) {
        // Simulate pricing — same logic as KubeFn's PricingFunction
        double discount = 0.15;
        double discountedPrice = basePrice * (1 - discount);
        double total = discountedPrice * quantity;
        return Map.of(
                "currency", "USD",
                "basePrice", basePrice,
                "discount", discount,
                "discountedUnitPrice", Math.round(discountedPrice * 100.0) / 100.0,
                "quantity", quantity,
                "total", Math.round(total * 100.0) / 100.0
        );
    }

    /**
     * Shipping service — estimates shipping cost and delivery.
     */
    @GetMapping("/shipping/estimate")
    public Map<String, Object> shippingEstimate(
            @RequestParam(defaultValue = "2.5") double weight,
            @RequestParam(defaultValue = "US-WEST") String zone) {
        // Simulate shipping estimate — same logic as KubeFn's ShippingFunction
        double cost = weight * 2.50 + 4.99;
        return Map.of(
                "method", "STANDARD",
                "carrier", "FastShip",
                "cost", Math.round(cost * 100.0) / 100.0,
                "currency", "USD",
                "estimatedDays", 5,
                "zone", zone,
                "weight", weight
        );
    }

    /**
     * Tax service — calculates sales tax.
     */
    @GetMapping("/tax/calculate")
    public Map<String, Object> taxCalculate(
            @RequestParam(defaultValue = "169.98") double subtotal,
            @RequestParam(defaultValue = "CA") String state) {
        // Simulate tax calculation — same logic as KubeFn's TaxFunction
        double taxRate = "CA".equals(state) ? 0.0825 : 0.06;
        double taxAmount = subtotal * taxRate;
        return Map.of(
                "subtotal", subtotal,
                "state", state,
                "taxRate", taxRate,
                "taxAmount", Math.round(taxAmount * 100.0) / 100.0,
                "totalWithTax", Math.round((subtotal + taxAmount) * 100.0) / 100.0
        );
    }

    /**
     * Fraud service — scores transaction risk.
     */
    @GetMapping("/fraud/check")
    public Map<String, Object> fraudCheck(
            @RequestParam(defaultValue = "user-001") String userId,
            @RequestParam(defaultValue = "169.98") double amount) {
        // Simulate fraud scoring — same logic as KubeFn's FraudCheckFunction
        double riskScore = 0.12; // Low risk
        return Map.of(
                "userId", userId,
                "amount", amount,
                "riskScore", riskScore,
                "riskLevel", "LOW",
                "approved", true,
                "checks", List.of("velocity", "geolocation", "device_fingerprint", "amount_pattern"),
                "decisionMs", 2
        );
    }

    /**
     * Assembly service — combines all service results into a final order.
     */
    @PostMapping("/checkout/assemble")
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkoutAssemble(@RequestBody Map<String, Object> parts) {
        // Assemble final order — same logic as KubeFn's CheckoutQuoteFunction
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", "ORD-" + System.currentTimeMillis());
        order.put("status", "CONFIRMED");
        order.put("auth", parts.get("auth"));
        order.put("inventory", parts.get("inventory"));
        order.put("pricing", parts.get("pricing"));
        order.put("shipping", parts.get("shipping"));
        order.put("tax", parts.get("tax"));
        order.put("fraudCheck", parts.get("fraud"));
        order.put("createdAt", Instant.now().toString());
        return order;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Object> stepTiming(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        return Map.of(
                "durationMs", String.format("%.3f", elapsed / 1_000_000.0),
                "durationNanos", elapsed,
                "includes", "HTTP round-trip + JSON serialize + JSON deserialize"
        );
    }
}
