package com.enterprise.db;

import com.kubefn.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
 * Creates an order with proper transaction handling. Reads the shared DataSource
 * from HeapExchange, checks stock, inserts the order, and decrements inventory —
 * all within a single transaction (commit/rollback).
 *
 * <p>Publishes the order result to heap key "order:latest" so downstream
 * functions (like the pipeline orchestrator) can read it zero-copy.
 *
 * <p>Expects JSON body: {@code {"productId": 1, "quantity": 2}}
 */
@FnRoute(path = "/db/orders", methods = {"POST"})
@FnGroup("database-demo")
public class OrderCreateFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Read the shared DataSource from HeapExchange — zero-copy, same object
        DataSource ds = ctx.heap()
                .get(DatabaseInitFunction.HEAP_KEY_DATASOURCE, DataSource.class)
                .orElseThrow(() -> new IllegalStateException(
                        "DataSource not on heap. Call POST /db/init first."));

        // Parse request — simple extraction from body string
        // In production you'd use a JSON library; here we keep it dependency-light
        String body = request.bodyAsString();
        int productId = extractInt(body, "productId", 1);
        int quantity = extractInt(body, "quantity", 1);

        Map<String, Object> orderResult = new LinkedHashMap<>();
        Connection conn = ds.getConnection();

        try {
            conn.setAutoCommit(false);

            // ── 1. Read product and check stock ─────────────────────────
            String productName;
            double productPrice;
            int currentStock;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, price, stock FROM products WHERE id = ?")) {
                ps.setInt(1, productId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return KubeFnResponse.badRequest(Map.of(
                                "error", "Product not found",
                                "productId", productId));
                    }
                    productName = rs.getString("name");
                    productPrice = rs.getDouble("price");
                    currentStock = rs.getInt("stock");
                }
            }

            if (currentStock < quantity) {
                conn.rollback();
                return KubeFnResponse.badRequest(Map.of(
                        "error", "Insufficient stock",
                        "productId", productId,
                        "requested", quantity,
                        "available", currentStock));
            }

            double total = productPrice * quantity;

            // ── 2. Insert order ─────────────────────────────────────────
            int orderId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO orders (product_id, quantity, total, status) VALUES (?, ?, ?, 'CONFIRMED')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, productId);
                ps.setInt(2, quantity);
                ps.setDouble(3, total);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    orderId = keys.getInt(1);
                }
            }

            // ── 3. Decrement stock atomically ───────────────────────────
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?")) {
                ps.setInt(1, quantity);
                ps.setInt(2, productId);
                ps.setInt(3, quantity);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    conn.rollback();
                    return KubeFnResponse.badRequest(Map.of(
                            "error", "Stock changed during transaction — rolled back",
                            "productId", productId));
                }
            }

            // ── 4. Commit the transaction ───────────────────────────────
            conn.commit();

            orderResult.put("orderId", orderId);
            orderResult.put("product", Map.of(
                    "id", productId,
                    "name", productName,
                    "unitPrice", productPrice
            ));
            orderResult.put("quantity", quantity);
            orderResult.put("total", total);
            orderResult.put("status", "CONFIRMED");

        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }

        // ── 5. Publish order result to HeapExchange ─────────────────────
        // Downstream functions can read this zero-copy
        ctx.heap().publish(DatabaseInitFunction.HEAP_KEY_ORDER_LATEST, orderResult, Map.class);

        long durationNanos = System.nanoTime() - startNanos;
        double durationMs = durationNanos / 1_000_000.0;

        orderResult.put("_meta", Map.of(
                "transactionTimeMs", String.format("%.3f", durationMs),
                "transactionIsolation", "READ_COMMITTED with optimistic stock check",
                "heapPublished", DatabaseInitFunction.HEAP_KEY_ORDER_LATEST,
                "note", "Same connection pool as ProductQuery and Pipeline — shared via HeapExchange"
        ));

        return KubeFnResponse.ok(orderResult);
    }

    /**
     * Simple integer extraction from a JSON-like string.
     * Looks for "key": value or "key":value patterns.
     */
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
