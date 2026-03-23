package com.kubefn.contracts;

/**
 * Shipping estimate — published by shipping functions,
 * consumed by assembly and notification functions.
 *
 * <p>Heap key convention: {@code shipping:estimate}
 */
public record ShippingEstimate(
        String method,
        String fromWarehouse,
        int estimatedDays,
        double cost
) {
    public static String heapKey() {
        return "shipping:estimate";
    }
}
