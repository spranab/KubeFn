package com.kubefn.contracts;

/**
 * Inventory status — published by inventory functions,
 * consumed by pricing, fulfillment, and assembly functions.
 *
 * <p>Heap key convention: {@code inventory:<sku>}
 */
public record InventoryStatus(
        String sku,
        int available,
        int reserved,
        String warehouse
) {
    public boolean isInStock() {
        return available - reserved > 0;
    }

    public int availableToSell() {
        return Math.max(0, available - reserved);
    }

    public String heapKey() {
        return "inventory:" + sku;
    }
}
