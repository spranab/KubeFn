package com.kubefn.contracts;

/**
 * Fraud scoring result — published by fraud detection functions,
 * consumed by decision and assembly functions.
 *
 * <p>Heap key convention: {@code fraud:result} or {@code fraud:<transactionId>}
 */
public record FraudScore(
        double riskScore,
        boolean approved,
        String reason,
        String model
) {
    /** Is this a high-risk transaction? */
    public boolean isHighRisk() {
        return riskScore > 0.7;
    }

    public static String heapKey() {
        return "fraud:result";
    }
}
