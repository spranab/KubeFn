package com.enterprise.db;

import com.kubefn.api.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
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
 * Initializes an H2 in-memory database with a HikariCP connection pool, then
 * publishes the DataSource to HeapExchange so every function in the group can
 * share the SAME pool object — zero-copy, zero-serialization.
 *
 * <p>This is the KEY demo: a live {@link DataSource} shared on the heap. In
 * microservices each service creates its own pool (wasting connections). Here,
 * one pool serves all functions at heap speed.
 */
@FnRoute(path = "/db/init", methods = {"POST"})
@FnGroup("database-demo")
public class DatabaseInitFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    /** Heap key for the shared DataSource — all functions use this constant. */
    static final String HEAP_KEY_DATASOURCE = "db:datasource";
    /** Heap key for the latest order result. */
    static final String HEAP_KEY_ORDER_LATEST = "order:latest";

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // ── 1. Create HikariCP pool backed by H2 in-memory DB ──────────
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:kubefn_demo;DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setPoolName("kubefn-shared-pool");
        config.setConnectionTimeout(3000);

        HikariDataSource dataSource = new HikariDataSource(config);

        // ── 2. Create schema and seed data ──────────────────────────────
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id          INT PRIMARY KEY,
                    name        VARCHAR(255) NOT NULL,
                    price       DECIMAL(10,2) NOT NULL,
                    stock       INT NOT NULL DEFAULT 0
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id          INT AUTO_INCREMENT PRIMARY KEY,
                    product_id  INT NOT NULL,
                    quantity    INT NOT NULL,
                    total       DECIMAL(10,2) NOT NULL,
                    status      VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (product_id) REFERENCES products(id)
                )
            """);

            // Insert 10 sample products
            stmt.execute("""
                MERGE INTO products (id, name, price, stock) VALUES
                (1,  'Wireless Keyboard',    49.99,  150),
                (2,  'USB-C Hub',            34.99,  200),
                (3,  'Mechanical Keyboard',  129.99,  75),
                (4,  '27\" 4K Monitor',      449.99,  30),
                (5,  'Ergonomic Mouse',       59.99, 120),
                (6,  'Webcam HD 1080p',       79.99,  90),
                (7,  'Laptop Stand',          39.99, 180),
                (8,  'Noise-Cancel Headset', 199.99,  60),
                (9,  'Thunderbolt Dock',     249.99,  40),
                (10, 'LED Desk Lamp',         29.99, 300)
            """);
        }

        // ── 3. Publish DataSource to HeapExchange — THE key moment ──────
        // Every other function reads this SAME object — zero-copy.
        // No connection pool duplication. No serialization. Same heap pointer.
        ctx.heap().publish(HEAP_KEY_DATASOURCE, dataSource, DataSource.class);

        long durationNanos = System.nanoTime() - startNanos;
        double durationMs = durationNanos / 1_000_000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "initialized");
        result.put("database", "H2 in-memory");
        result.put("pool", Map.of(
                "name", "kubefn-shared-pool",
                "maxConnections", 20,
                "minIdle", 5
        ));
        result.put("tables", Map.of(
                "products", "10 rows seeded",
                "orders", "empty — ready for use"
        ));
        result.put("heapKey", HEAP_KEY_DATASOURCE);
        result.put("_meta", Map.of(
                "initTimeMs", String.format("%.3f", durationMs),
                "note", "DataSource published to HeapExchange. All functions share this SAME pool object."
        ));

        return KubeFnResponse.ok(result);
    }
}
