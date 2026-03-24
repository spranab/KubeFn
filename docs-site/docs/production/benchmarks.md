# Benchmarks

## Methodology

- Tool: `hey` (HTTP load generator)
- Location: in-cluster (same namespace, no ingress)
- Warmup: 1000 requests discarded
- Measurement: 10,000 requests, 10 concurrent connections
- Full HTTP cycle: Netty receives request, routes to function, function executes, response returned

## Results

### JVM Runtime -- Checkout Pipeline (4 steps)

Pricing -> Tax -> Inventory -> Fraud, each reading/writing HeapExchange.

| Metric | Value |
|--------|-------|
| p50    | 5.7ms |
| p95    | 8.2ms |
| p99    | 12.1ms |
| Throughput | 1,650 req/s |

### Python Runtime -- ML Pipeline (3 steps)

Feature extraction -> Scoring -> Post-processing.

| Metric | Value |
|--------|-------|
| p50    | 5.4ms |
| p95    | 7.8ms |
| p99    | 11.3ms |
| Throughput | 1,800 req/s |

### Node.js Runtime -- API Gateway (3 steps)

Auth -> Rate limit -> Route.

| Metric | Value |
|--------|-------|
| p50    | 3.0ms |
| p95    | 4.5ms |
| p99    | 6.8ms |
| Throughput | 3,200 req/s |

## Comparison to Microservices

Same logic split across separate Deployments communicating via HTTP/JSON:

| Pipeline | KubeFn p50 | Microservices p50 | Speedup |
|----------|-----------|-------------------|---------|
| Checkout (4 steps) | 5.7ms | 42ms | 7.4x |
| ML (3 steps) | 5.4ms | 28ms | 5.2x |
| Gateway (3 steps) | 3.0ms | 18ms | 6.0x |

The speedup comes from eliminating inter-service HTTP calls and JSON serialization/deserialization.

## Known Limitations

- Benchmarks are single-organism (one pod). Multi-replica adds load balancer latency.
- Python and Node.js runtimes use runtime-specific shared memory, not JVM heap.
- Numbers will vary with function complexity -- these are lightweight functions.
- GC pauses (JVM) show up in p99. ZGC keeps them under 15ms.

## Reproduce

```bash
# Deploy the benchmark functions
kubefn deploy examples/benchmarks/checkout-bench.jar checkout

# Run the benchmark
hey -n 10000 -c 10 -m POST http://kubefn-runtime:8080/bench/checkout
```

Benchmark source code is in `examples/benchmarks/`.

## Next

- [Should I Use KubeFn?](../migration/should-i-use.md)
- [Scaling](scaling.md)
