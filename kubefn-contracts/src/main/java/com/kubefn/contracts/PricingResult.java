package com.kubefn.contracts;

/**
 * Pricing result — published by pricing functions,
 * consumed by tax, shipping, fraud, and assembly functions.
 *
 * <p>Heap key convention: {@code pricing:current} or {@code pricing:<orderId>}
 */
public record PricingResult(
        String currency,
        double basePrice,
        double discount,
        double finalPrice
) {
    /** Calculate discounted price. */
    public double discountAmount() {
        return basePrice * discount;
    }

    /** Standard heap key. */
    public static String heapKey() {
        return "pricing:current";
    }
}
