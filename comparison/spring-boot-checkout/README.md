# Spring Boot Checkout — Microservices Comparison

This is the microservices equivalent of KubeFn's checkout pipeline. It implements the **exact same business logic** across 7 steps, but uses the traditional microservices pattern: each step is a separate REST endpoint (simulating separate microservices), and the orchestrator calls each one via HTTP.

## Architecture

```
POST /checkout (orchestrator)
  ├── GET  /auth/verify         → Auth service (HTTP + JSON serialize/deserialize)
  ├── GET  /inventory/check     → Inventory service (HTTP + JSON serialize/deserialize)
  ├── GET  /pricing/calculate   → Pricing service (HTTP + JSON serialize/deserialize)
  ├── GET  /shipping/estimate   → Shipping service (HTTP + JSON serialize/deserialize)
  ├── GET  /tax/calculate       → Tax service (HTTP + JSON serialize/deserialize)
  ├── GET  /fraud/check         → Fraud service (HTTP + JSON serialize/deserialize)
  └── POST /checkout/assemble   → Assembly service (HTTP + JSON serialize/deserialize)
```

Each hop involves:
- HTTP request construction
- JSON serialization of the response
- Network round-trip (localhost, but still TCP)
- JSON deserialization in the orchestrator
- Object allocation for every intermediate result

## What this measures

The overhead of the **microservices communication pattern** — not the business logic itself. The business logic is identical between KubeFn and this Spring Boot app. The difference is:

| Aspect | KubeFn | Spring Boot (this app) |
|--------|--------|----------------------|
| Inter-service calls | 0 (in-memory) | 7 HTTP round-trips |
| Serialization | 0 (zero-copy heap) | 14 (7 serialize + 7 deserialize) |
| Connection pools | 1 shared | 1 per service (simulated) |
| Object copies | 0 (same heap pointer) | 7+ (one per deserialization) |
| Total overhead | ~0.01ms | ~15-50ms |

## How to compare

```bash
# Start Spring Boot (7 microservice endpoints in one app)
cd comparison/spring-boot-checkout
./gradlew bootRun

# Run benchmark
curl localhost:9090/benchmark?runs=1000

# Compare with KubeFn
curl localhost:8080/checkout/full  # KubeFn (same logic, zero serialization)
curl localhost:9090/checkout       # Spring Boot (same logic, 7 HTTP hops)
```

## Fairness

This comparison is designed to be **fair**:
- Same business logic (auth, inventory, pricing, shipping, tax, fraud, assembly)
- Same data structures and computations
- Same JVM (Java 21)
- All endpoints run in the same process (localhost) — this actually **favors** Spring Boot since real microservices would have network latency between hosts
- The only difference is architecture: shared heap vs HTTP+JSON between services

In a real deployment, the Spring Boot version would be **even slower** because services would be on different hosts with real network latency, not just localhost loopback.
