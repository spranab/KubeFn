# Admin API Reference

All admin endpoints are served on port `8081` by default. All return JSON unless noted.

## Health & Readiness

### GET /admin/health

Full health check with function count and uptime.

```json
{
  "status": "UP",
  "functions": 95,
  "uptime_ms": 3642000,
  "heap_objects": 12,
  "jvm_heap_used_mb": 342
}
```

### GET /admin/ready

Kubernetes readiness probe. Returns 200 when all functions are loaded.

```json
{
  "status": "READY",
  "functionCount": 95
}
```

## Functions

### GET /admin/functions

Lists all loaded functions with routes, groups, and annotations.

```json
{
  "count": 95,
  "functions": [
    {
      "class": "com.example.PricingFunction",
      "group": "checkout",
      "route": "/api/pricing",
      "methods": ["POST"],
      "annotations": ["@FnRoute", "@FnGroup", "@Produces"]
    }
  ]
}
```

## HeapExchange

### GET /admin/heap

Heap statistics and current entries.

```json
{
  "objectCount": 6,
  "publishCount": 8,
  "getCount": 142,
  "hitRate": 0.97,
  "entries": [
    {
      "key": "pricing:current",
      "type": "PricingResult",
      "publishedBy": "PricingFunction",
      "publishedAt": "2026-03-24T10:00:00Z"
    }
  ]
}
```

## Observability

### GET /admin/prometheus

Prometheus metrics in text exposition format. See [Metrics Reference](metrics.md).

```
# HELP kubefn_requests_total Total requests
# TYPE kubefn_requests_total counter
kubefn_requests_total 14523
kubefn_function_duration_p99_ms{group="checkout",function="PricingFunction"} 2.1
```

### GET /admin/breakers

Circuit breaker states for all functions.

```json
{
  "breakers": [
    {"function": "PaymentGateway", "state": "CLOSED", "failureCount": 0, "lastFailure": null},
    {"function": "InventoryCheck", "state": "OPEN", "failureCount": 12, "lastFailure": "2026-03-24T09:55:00Z"}
  ]
}
```

### GET /admin/traces/recent

Recent request traces (last 100).

```json
{
  "traces": [
    {
      "requestId": "req-abc-123",
      "path": "/api/checkout",
      "duration_ms": 5.7,
      "functions": ["PricingFunction", "TaxFunction", "FraudDetector"],
      "timestamp": "2026-03-24T10:00:00Z"
    }
  ]
}
```

### GET /admin/traces/{requestId}

Single trace detail with per-function timing and heap interactions.

```json
{
  "requestId": "req-abc-123",
  "path": "/api/checkout",
  "duration_ms": 5.7,
  "steps": [
    {"function": "PricingFunction", "duration_ms": 1.2, "heapPublished": ["pricing:current"]},
    {"function": "TaxFunction", "duration_ms": 0.8, "heapRead": ["pricing:current"], "heapPublished": ["tax:calculated"]},
    {"function": "FraudDetector", "duration_ms": 1.1, "heapRead": ["pricing:current", "auth:user-42"], "heapPublished": ["fraud:result"]}
  ]
}
```

## Scheduling

### GET /admin/scheduler

Lists all scheduled functions with next run times.

```json
{
  "scheduled": [
    {
      "function": "RevenueReport",
      "cron": "0 */5 * * * *",
      "nextRun": "2026-03-24T10:05:00Z",
      "lastRun": "2026-03-24T10:00:00Z",
      "status": "IDLE"
    }
  ]
}
```

## Lifecycle & Memory

### GET /admin/lifecycle

Heap and memory lifecycle statistics.

```json
{
  "heapPublishes": 8420,
  "heapEvictions": 312,
  "heapCurrentObjects": 6,
  "jvmHeapUsedMb": 342,
  "jvmHeapMaxMb": 1536,
  "gcPauseMs": 1.2
}
```

### GET /admin/drain

Current drain status (used during graceful shutdown).

```json
{
  "draining": false,
  "activeRequests": 3,
  "drainingFunctions": []
}
```

## Dependency Graph

### GET /admin/graph

Function dependency graph based on `@Consumes` and `@Produces` annotations.

```json
{
  "nodes": ["PricingFunction", "TaxFunction", "FraudDetector"],
  "edges": [
    {"from": "PricingFunction", "to": "TaxFunction", "key": "pricing:current"},
    {"from": "PricingFunction", "to": "FraudDetector", "key": "pricing:current"}
  ]
}
```

## Debug UI

### GET /admin/ui

Serves an HTML dashboard with live function timeline, heap viewer, and dependency graph. Open in a browser.

## Actions

### POST /admin/reload

Triggers a hot-reload of all function JARs.

```json
{
  "reloaded": 95,
  "failed": 0,
  "duration_ms": 120
}
```

### POST /admin/replay/{requestId}

Replays a previously traced request for debugging.

```json
{
  "originalRequestId": "req-abc-123",
  "replayRequestId": "req-replay-456",
  "status": 200,
  "duration_ms": 6.1
}
```
