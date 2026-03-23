/**
 * KubeFn Contracts — shared type definitions for HeapExchange objects.
 *
 * <p>This package defines the "contract" between independently deployed
 * functions. When Function A publishes an object to HeapExchange and
 * Function B consumes it, both must agree on the type. These records
 * are that agreement.
 *
 * <p>Think of this as the KubeFn equivalent of Protobuf definitions
 * in a microservices architecture. Instead of defining wire formats,
 * you define heap object shapes.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // Producer (PricingFunction):
 * var result = new PricingResult("USD", 99.99, 0.15, 84.99);
 * ctx.heap().publish("pricing:current", result, PricingResult.class);
 *
 * // Consumer (TaxFunction) — gets the SAME object, zero-copy:
 * PricingResult pricing = ctx.heap().get("pricing:current", PricingResult.class)
 *     .orElseThrow();
 * double tax = pricing.finalPrice() * 0.0825;
 * }</pre>
 *
 * <h3>Developer workflow:</h3>
 * <ol>
 *   <li>Define your heap object as a record in this module</li>
 *   <li>Both producer and consumer depend on kubefn-contracts</li>
 *   <li>Develop independently — the contract is the agreement</li>
 *   <li>Schema evolution: add fields (minor version), change types (major version)</li>
 * </ol>
 */
package com.kubefn.contracts;
