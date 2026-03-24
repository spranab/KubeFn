# Prometheus Metrics Reference

All metrics exposed at `GET /admin/prometheus` on port 8081.

## Request Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `kubefn_requests_total` | counter | -- | Total HTTP requests handled by the runtime |
| `kubefn_errors_total` | counter | -- | Total requests that resulted in an error (4xx/5xx) |
| `kubefn_function_requests_total` | counter | `group`, `function` | Requests per function |
| `kubefn_function_errors_total` | counter | `group`, `function` | Errors per function |

## Latency Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `kubefn_function_duration_p50_ms` | gauge | `group`, `function` | 50th percentile latency in ms |
| `kubefn_function_duration_p95_ms` | gauge | `group`, `function` | 95th percentile latency in ms |
| `kubefn_function_duration_p99_ms` | gauge | `group`, `function` | 99th percentile latency in ms |

## HeapExchange Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `kubefn_heap_objects` | gauge | -- | Current number of objects on the heap |
| `kubefn_heap_publishes_total` | counter | -- | Total heap publish operations |
| `kubefn_heap_gets_total` | counter | -- | Total heap get operations |
| `kubefn_heap_hit_rate` | gauge | -- | Ratio of successful gets to total gets (0.0-1.0) |

## JVM Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `kubefn_jvm_heap_used_mb` | gauge | -- | Current JVM heap usage in MB |
| `kubefn_jvm_heap_max_mb` | gauge | -- | Maximum JVM heap size in MB |
| `kubefn_uptime_seconds` | gauge | -- | Runtime uptime in seconds |

## Circuit Breaker Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `kubefn_breaker_trips_total` | counter | -- | Total circuit breaker trips across all functions |

## Example PromQL Queries

Total request rate:

```promql
rate(kubefn_requests_total[5m])
```

Error rate by function:

```promql
rate(kubefn_function_errors_total{group="checkout"}[5m])
```

P99 latency for checkout group:

```promql
kubefn_function_duration_p99_ms{group="checkout"}
```

Heap hit rate (should be close to 1.0):

```promql
kubefn_heap_hit_rate
```

JVM heap pressure (alert if > 0.85):

```promql
kubefn_jvm_heap_used_mb / kubefn_jvm_heap_max_mb
```

## Grafana

Import the KubeFn dashboard from `deploy/grafana-dashboard.json` or use dashboard ID `19842` from Grafana.com. Panels include request rate, latency percentiles, heap usage, and circuit breaker state.
