# Scaling

## Horizontal Scaling

Run multiple replicas behind a Service:

```yaml
runtime:
  replicas: 3
```

Each replica is an independent organism with its own heap. Heap state is **not shared across replicas** -- each replica has its own HeapExchange.

For stateless functions, this works like any horizontal scaling. For stateful heap patterns, use sticky sessions or an external store as the source of truth.

## Per-Function Concurrency

Limit concurrent invocations per function:

```java
@FnRoute(path = "/api/heavy", methods = {"POST"}, maxConcurrency = 4)
```

Requests exceeding the limit receive HTTP 429. This prevents a slow function from consuming all threads.

## Queue-Based Autoscaling

For `@FnQueue` functions, scale based on queue depth:

```yaml
autoscaling:
  enabled: true
  minReplicas: 1
  maxReplicas: 10
  metrics:
    - type: External
      external:
        metric:
          name: kubefn_queue_depth
          selector:
            matchLabels:
              queue: order-events
        target:
          type: AverageValue
          averageValue: 100
```

## Memory Pressure and HeapGuard

HeapGuard monitors heap usage and takes action when memory pressure is high:

| Threshold | Action |
|-----------|--------|
| 70% | Log warning |
| 85% | Evict expired heap entries |
| 95% | Reject new publishes, return 503 |

Configure thresholds:

```yaml
runtime:
  heap:
    maxSize: 2g
    guard:
      warnThreshold: 0.70
      evictThreshold: 0.85
      rejectThreshold: 0.95
```

## When to Split Organisms

Split into separate organisms when:

- Functions have vastly different resource profiles (CPU-heavy vs memory-heavy)
- Teams need independent deploy cadences
- You need process-level fault isolation
- Heap is too large for a single JVM

Keep in one organism when:

- Functions frequently share heap state
- You want sub-millisecond inter-function calls
- Functions are maintained by the same team

## Next

- [Benchmarks](benchmarks.md)
- [Deployment](deployment.md)
