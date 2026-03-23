package com.kubefn.contracts;

/**
 * Central registry of all HeapExchange key conventions.
 *
 * <p>This serves as documentation and prevents key collisions.
 * Every heap key used across functions should be documented here.
 *
 * <h3>Convention:</h3>
 * <ul>
 *   <li>{@code domain:identifier} — e.g., {@code auth:user-001}</li>
 *   <li>Static keys use the domain only — e.g., {@code pricing:current}</li>
 *   <li>Dynamic keys use domain + ID — e.g., {@code inventory:SKU-42}</li>
 * </ul>
 */
public final class HeapKeys {

    private HeapKeys() {}

    // ── Authentication ──────────────────────────────────────────
    /** Auth context for a user. Key: auth:{userId} */
    public static String auth(String userId) { return "auth:" + userId; }

    // ── Pricing ─────────────────────────────────────────────────
    /** Current pricing result. */
    public static final String PRICING_CURRENT = "pricing:current";

    // ── Inventory ───────────────────────────────────────────────
    /** Inventory status for a SKU. Key: inventory:{sku} */
    public static String inventory(String sku) { return "inventory:" + sku; }

    // ── Fraud ───────────────────────────────────────────────────
    /** Fraud scoring result. */
    public static final String FRAUD_RESULT = "fraud:result";

    // ── Shipping ────────────────────────────────────────────────
    /** Shipping estimate. */
    public static final String SHIPPING_ESTIMATE = "shipping:estimate";

    // ── Tax ─────────────────────────────────────────────────────
    /** Tax calculation. */
    public static final String TAX_CALCULATED = "tax:calculated";

    // ── ML Pipeline ─────────────────────────────────────────────
    /** ML feature vector. Key: ml:features */
    public static final String ML_FEATURES = "ml:features";
    /** ML prediction result. Key: ml:prediction */
    public static final String ML_PREDICTION = "ml:prediction";
}
