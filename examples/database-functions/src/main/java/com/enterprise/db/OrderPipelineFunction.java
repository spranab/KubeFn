package com.enterprise.db;

import com.kubefn.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * BEFORE (microservices): Each service has its own connection pool.
 *   Product service: 20 connections
 *   Order service: 20 connections
 *   Checkout service: 20 connections
 *   Total: 60 connections to same DB for 3 services
 *
 * AFTER (KubeFn): One shared pool via HeapExchange.
 *   All functions: 20 connections (shared zero-copy)
 *   Total: 20 connections for unlimited functions
 *   Plus: no serialization of query results between services
 */

/**
 * Orchestrator that composes ProductQuery + OrderCreate + heap reads into a
 * complete checkout pipeline. Demonstrates the FULL pattern:
 *
 * <ol>
 *   <li>Shared DB pool (HeapExchange) — no pool duplication</li>
 *   <li>Function composition — in-memory, no HTTP hops</li>
 *   <li>Heap sharing — query results and order state flow between steps zero-copy</li>
 *   <li>Timing — each step is measured independently</li>
 * </ol>
 *
 * <p>Expects JSON body: {@code {"productId": 3, "quantity": 1}}
 */
@FnRoute(path = "/db/checkout", methods = {"POST"})
@FnGroup("database-demo")
public class OrderPipelineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stepTimings = new ArrayList<>();

        // ── Step 1: Verify DB pool is on the heap ───────────────────────
        long stepStart = System.nanoTime();
        DataSource ds = ctx.heap()
                .get(DatabaseInitFunction.HEAP_KEY_DATASOURCE, DataSource.class)
                .orElseThrow(() -> new IllegalStateException(
                        "DataSource not on heap. Call POST /db/init first."));
        stepTimings.add(stepTiming("heap-datasource-lookup", stepStart));

        // ── Step 2: Query product catalog (via sibling function) ────────
        stepStart = System.nanoTime();
        ctx.getFunction(ProductQueryFunction.class).handle(request);
        stepTimings.add(stepTiming("product-query", stepStart));

        // ── Step 3: Read pricing from DB for the requested product ──────
        stepStart = System.nanoTime();
        String body = request.bodyAsString();
        int productId = extractInt(body, "productId", 1);
        int quantity = extractInt(body, "quantity", 1);

        Map<String, Object> pricing = new LinkedHashMap<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, price, stock FROM products WHERE id = ?")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double unitPrice = rs.getDouble("price");
                    pricing.put("productId", productId);
                    pricing.put("productName", rs.getString("name"));
                    pricing.put("unitPrice", unitPrice);
                    pricing.put("quantity", quantity);
                    pricing.put("subtotal", unitPrice * quantity);
                    pricing.put("availableStock", rs.getInt("stock"));
                }
            }
        }
        stepTimings.add(stepTiming("pricing-lookup", stepStart));

        // ── Step 4: Calculate tax ───────────────────────────────────────
        stepStart = System.nanoTime();
        double subtotal = (double) pricing.getOrDefault("subtotal", 0.0);
        double taxRate = 0.0825;
        double taxAmount = subtotal * taxRate;
        Map<String, Object> tax = Map.of(
                "subtotal", subtotal,
                "taxRate", taxRate,
                "taxAmount", Math.round(taxAmount * 100.0) / 100.0,
                "totalWithTax", Math.round((subtotal + taxAmount) * 100.0) / 100.0
        );
        stepTimings.add(stepTiming("tax-calculation", stepStart));

        // ── Step 5: Create the order (via sibling function) ─────────────
        stepStart = System.nanoTime();
        KubeFnResponse orderResponse = ctx.getFunction(OrderCreateFunction.class).handle(request);
        stepTimings.add(stepTiming("order-create", stepStart));

        // ── Step 6: Read order result from heap (zero-copy) ─────────────
        stepStart = System.nanoTime();
        @SuppressWarnings("unchecked")
        Map<String, Object> orderResult = ctx.heap()
                .get(DatabaseInitFunction.HEAP_KEY_ORDER_LATEST, Map.class)
                .orElse(Map.of("status", "unknown"));
        stepTimings.add(stepTiming("heap-order-read", stepStart));

        // ── Step 7: Verify updated stock from DB ────────────────────────
        stepStart = System.nanoTime();
        Map<String, Object> updatedStock = new LinkedHashMap<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stock FROM products WHERE id = ?")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    updatedStock.put("productId", productId);
                    updatedStock.put("remainingStock", rs.getInt("stock"));
                }
            }
        }
        stepTimings.add(stepTiming("stock-verification", stepStart));

        long pipelineEnd = System.nanoTime();
        double totalMs = (pipelineEnd - pipelineStart) / 1_000_000.0;

        // ── Assemble complete checkout result ───────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkout", Map.of(
                "order", orderResult,
                "pricing", pricing,
                "tax", tax,
                "stock", updatedStock
        ));
        result.put("_meta", Map.of(
                "pipelineSteps", stepTimings.size(),
                "totalTimeMs", String.format("%.3f", totalMs),
                "steps", stepTimings,
                "sharedResources", Map.of(
                        "connectionPool", "1 HikariCP pool shared by all functions via HeapExchange",
                        "heapObjects", ctx.heap().keys().size(),
                        "zeroCopy", true
                ),
                "note", "DB reads, business logic, DB writes, and heap sharing — " +
                        "all in one process, one pool, zero serialization"
        ));

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> stepTiming(String name, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("step", name);
        timing.put("durationMs", String.format("%.3f", elapsed / 1_000_000.0));
        timing.put("durationNanos", elapsed);
        return timing;
    }

    private int extractInt(String json, String key, int defaultValue) {
        if (json == null || json.isBlank()) return defaultValue;
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return defaultValue;
        StringBuilder sb = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (Character.isDigit(c) || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
