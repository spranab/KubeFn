package com.kubefn.contracts;

/**
 * Tax calculation result — published by tax functions,
 * consumed by assembly and payment functions.
 *
 * <p>Heap key convention: {@code tax:calculated}
 */
public record TaxCalculation(
        double subtotal,
        double taxRate,
        double taxAmount,
        double total
) {
    public static String heapKey() {
        return "tax:calculated";
    }
}
