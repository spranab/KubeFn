# CLI Reference

The `kubefn` CLI manages function development, deployment, and runtime inspection.

## kubefn init

Scaffold a new function project.

```bash
kubefn init my-function
kubefn init my-function --lang kotlin --package com.example.billing
```

| Flag | Type | Default | Description |
|---|---|---|---|
| `--lang` | `string` | `java` | Language: `java`, `kotlin`, `scala`, `groovy` |
| `--package` | `string` | `com.kubefn.functions` | Base package name |

Creates a Gradle project with `kubefn-api` and `kubefn-contracts` as `compileOnly` dependencies, a sample handler, and a test.

## kubefn dev

Start the local development runtime with hot-reload.

```bash
kubefn dev
kubefn dev --port 9090 --functions-dir ./my-functions
```

| Flag | Type | Default | Description |
|---|---|---|---|
| `--port` | `int` | `8080` | HTTP port for function requests |
| `--functions-dir` | `string` | `./functions` | Directory containing function JARs or class files |

Watches the functions directory for changes and reloads automatically.

## kubefn deploy

Deploy a function JAR to a running organism.

```bash
kubefn deploy --jar build/libs/my-function.jar --group checkout
```

| Flag | Type | Default | Description |
|---|---|---|---|
| `--jar` | `string` | (required) | Path to the function JAR |
| `--group` | `string` | (required) | Function group name |

## kubefn functions

List all deployed functions.

```bash
kubefn functions
kubefn functions --url http://staging-kubefn:8081
```

| Flag | Type | Default | Description |
|---|---|---|---|
| `--url` | `string` | `http://localhost:8081` | Admin API URL |

```
GROUP       FUNCTION              ROUTE                METHODS
checkout    PricingFunction       /api/pricing         POST
checkout    TaxFunction           /api/tax             POST
risk        FraudDetector         /api/fraud/check     POST
reports     RevenueReport         (scheduled)          -
```

## kubefn status

Show runtime status including uptime, function count, and JVM stats.

```bash
kubefn status
```

```
Status:     UP
Functions:  95
Uptime:     2h 14m
JVM Heap:   342 / 1536 MB
GC Pause:   1.2ms avg
```

## kubefn heap

Inspect the HeapExchange state.

```bash
kubefn heap
```

```
KEY                TYPE              PUBLISHED BY        AGE
pricing:current    PricingResult     PricingFunction     12s
tax:calculated     TaxCalculation    TaxFunction         12s
fraud:result       FraudScore        FraudDetector       11s
auth:user-42       AuthContext       AuthFunction        45s
```

## kubefn breakers

Show circuit breaker states.

```bash
kubefn breakers
```

```
FUNCTION            STATE     FAILURES   LAST FAILURE
PaymentGateway      CLOSED    0          -
InventoryCheck      OPEN      12         2m ago
EmailSender         HALF      3          30s ago
```

## kubefn trace

View a specific request trace.

```bash
kubefn trace req-abc-123
```

```
Request: req-abc-123
Path:    /api/checkout
Total:   5.7ms

STEP  FUNCTION           DURATION  HEAP READ            HEAP PUBLISHED
1     PricingFunction    1.2ms     -                    pricing:current
2     TaxFunction        0.8ms     pricing:current      tax:calculated
3     FraudDetector      1.1ms     pricing:current,     fraud:result
                                   auth:user-42
```

## kubefn top

Live dashboard showing request rates and function latencies (refreshes every second).

```bash
kubefn top
```

```
FUNCTION              REQ/s   P50     P95     P99     ERRORS
PricingFunction       142     0.8ms   1.5ms   2.1ms   0
TaxFunction           142     0.5ms   0.9ms   1.2ms   0
FraudDetector         138     1.0ms   1.8ms   2.5ms   4
```

## kubefn logs

Stream logs for a specific function or all functions.

```bash
kubefn logs FraudDetector    # specific function
kubefn logs                  # all functions
```

## kubefn version

Print CLI and runtime versions.

```bash
kubefn version
```

```
CLI:     0.4.0
Runtime: 0.4.0 (connected to localhost:8081)
```
