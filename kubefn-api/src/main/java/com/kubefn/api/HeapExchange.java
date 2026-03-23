package com.kubefn.api;

import java.util.Optional;
import java.util.Set;

/**
 * The Shared Object Graph Fabric — KubeFn's revolutionary zero-copy data plane.
 *
 * <p>Functions publish and consume typed, immutable heap objects directly.
 * No serialization. No network. Same memory address. This is the foundation
 * of Memory-Continuous Architecture.
 *
 * <p>Objects in the HeapExchange are wrapped in {@link HeapCapsule} — immutable
 * snapshots with version metadata, publisher info, and lifecycle scoping.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Function A: parse once, publish to the fabric
 * ProductCatalog catalog = parseFromJson(payload);
 * ctx.heap().publish("catalog", catalog, ProductCatalog.class);
 *
 * // Function B: read directly — ZERO serialization, same heap object
 * ProductCatalog catalog = ctx.heap().get("catalog", ProductCatalog.class)
 *     .orElseThrow();
 * // This IS the same object in memory. Not a copy. Not deserialized.
 * }</pre>
 */
public interface HeapExchange {

    /**
     * Publish a typed object to the shared heap fabric.
     * The object becomes visible to all functions in the namespace.
     * Objects should be effectively immutable once published.
     *
     * @param key  unique key for this object
     * @param value the object to publish (should be immutable)
     * @param type  the type of the object (for type-safe retrieval)
     * @param <T>   the object type
     * @return a capsule wrapping the published object
     */
    <T> HeapCapsule<T> publish(String key, T value, Class<T> type);

    /**
     * Publish an object with inferred type. Convenience for dynamic/untyped usage.
     */
    @SuppressWarnings("unchecked")
    default <T> HeapCapsule<T> publish(String key, T value) {
        return publish(key, value, (Class<T>) value.getClass());
    }

    /**
     * Get a shared object by key. Returns a direct heap reference —
     * zero-copy, zero-serialization.
     *
     * @param key  the key to look up
     * @param type expected type
     * @param <T>  the object type
     * @return the live heap object, or empty if not published
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Get the full capsule with metadata (version, publisher, timestamp).
     */
    <T> Optional<HeapCapsule<T>> getCapsule(String key, Class<T> type);

    /**
     * Remove an object from the exchange.
     */
    boolean remove(String key);

    /**
     * List all keys currently in the exchange.
     */
    Set<String> keys();

    /**
     * Check if a key exists.
     */
    boolean contains(String key);

    // ── Typed HeapKey<T> API (compile-time safe) ──────────────────────

    /**
     * Get a shared object by typed key. Compile-time safe — wrong type won't compile.
     *
     * <pre>{@code
     * Optional<PricingResult> pricing = ctx.heap().get(HeapKeys.PRICING_CURRENT);
     * }</pre>
     */
    default <T> Optional<T> get(HeapKey<T> key) {
        return get(key.name(), key.type());
    }

    /**
     * Get a shared object or throw if missing. Use for required dependencies.
     *
     * <pre>{@code
     * PricingResult pricing = ctx.heap().require(HeapKeys.PRICING_CURRENT);
     * }</pre>
     *
     * @throws IllegalStateException if the key is not present on the heap
     */
    default <T> T require(HeapKey<T> key) {
        return get(key.name(), key.type())
                .orElseThrow(() -> new IllegalStateException(
                        "Required heap key not found: " + key.name() +
                        " (type=" + key.type().getSimpleName() + "). " +
                        "The producer function may not have run yet."));
    }

    /**
     * Get a shared object or return a default value.
     *
     * <pre>{@code
     * PricingResult pricing = ctx.heap().getOrDefault(HeapKeys.PRICING_CURRENT, defaultPricing);
     * }</pre>
     */
    default <T> T getOrDefault(HeapKey<T> key, T defaultValue) {
        return get(key.name(), key.type()).orElse(defaultValue);
    }

    /**
     * Publish a typed object using a HeapKey. Compile-time safe.
     *
     * <pre>{@code
     * ctx.heap().publish(HeapKeys.TAX_CALCULATED, taxResult);
     * }</pre>
     */
    default <T> HeapCapsule<T> publish(HeapKey<T> key, T value) {
        return publish(key.name(), value, key.type());
    }

    /**
     * Check if a typed key exists on the heap.
     */
    default boolean contains(HeapKey<?> key) {
        return contains(key.name());
    }

    /**
     * Remove a typed key from the heap.
     */
    default boolean remove(HeapKey<?> key) {
        return remove(key.name());
    }
}
