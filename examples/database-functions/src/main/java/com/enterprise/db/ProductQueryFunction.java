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
 * Queries products from the shared database. Reads the DataSource from
 * HeapExchange — the SAME pool object that {@link DatabaseInitFunction}
 * published. Zero-copy, zero-serialization.
 *
 * <p>Supports optional {@code ?search=term} query param for filtering.
 */
@FnRoute(path = "/db/products", methods = {"GET"})
@FnGroup("database-demo")
public class ProductQueryFunction implements KubeFnHandler, FnContextAware {
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

        String search = request.queryParam("search").orElse(null);

        List<Map<String, Object>> products = new ArrayList<>();

        try (Connection conn = ds.getConnection()) {
            String sql;
            PreparedStatement ps;

            if (search != null && !search.isBlank()) {
                sql = "SELECT id, name, price, stock FROM products WHERE LOWER(name) LIKE ? ORDER BY id";
                ps = conn.prepareStatement(sql);
                ps.setString(1, "%" + search.toLowerCase() + "%");
            } else {
                sql = "SELECT id, name, price, stock FROM products ORDER BY id";
                ps = conn.prepareStatement(sql);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> product = new LinkedHashMap<>();
                    product.put("id", rs.getInt("id"));
                    product.put("name", rs.getString("name"));
                    product.put("price", rs.getDouble("price"));
                    product.put("stock", rs.getInt("stock"));
                    products.add(product);
                }
            }
            ps.close();
        }

        long durationNanos = System.nanoTime() - startNanos;
        double durationMs = durationNanos / 1_000_000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("products", products);
        result.put("count", products.size());
        if (search != null) {
            result.put("search", search);
        }
        result.put("_meta", Map.of(
                "queryTimeMs", String.format("%.3f", durationMs),
                "dataSource", "shared via HeapExchange (zero-copy)",
                "note", "Same DataSource object as all other functions — no pool duplication"
        ));

        return KubeFnResponse.ok(result);
    }
}
