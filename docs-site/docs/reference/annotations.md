# Annotations Reference

All annotations available to KubeFn function authors.

## @FnRoute

Routes HTTP requests to a function handler.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `path` | `String` | (required) | URL path, supports path params: `/api/users/{id}` |
| `methods` | `String[]` | `{"GET"}` | Allowed HTTP methods |

```java
@FnRoute(path = "/api/checkout", methods = {"POST"})
public class CheckoutFunction implements KubeFnHandler { ... }
```

```java
@FnRoute(path = "/api/products/{id}", methods = {"GET", "PUT", "DELETE"})
public class ProductFunction implements KubeFnHandler { ... }
```

## @FnGroup

Assigns a function to a deployment group. Functions in the same group share a classloader.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `value` | `String` | (required) | Group name |

```java
@FnGroup("checkout")
public class PricingFunction implements KubeFnHandler { ... }
```

## @FnSchedule

Runs a function on a cron schedule.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `cron` | `String` | (required) | 6-field cron expression (sec min hour day month weekday) |
| `timezone` | `String` | `"UTC"` | Timezone for cron evaluation |
| `runOnStart` | `boolean` | `false` | Execute immediately when the function is deployed |
| `timeoutMs` | `long` | `300000` | Maximum execution time in milliseconds |
| `skipIfRunning` | `boolean` | `true` | Skip this tick if the previous execution is still running |

```java
@FnSchedule(cron = "0 */5 * * * *", timezone = "America/New_York")
@FnGroup("reports")
public class RevenueReport implements KubeFnHandler { ... }
```

```java
@FnSchedule(cron = "0 0 2 * * *", runOnStart = true, timeoutMs = 600000, skipIfRunning = true)
@FnGroup("maintenance")
public class DataCleanup implements KubeFnHandler { ... }
```

## @FnQueue

Binds a function to a message queue topic.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `topic` | `String` | (required) | Queue topic to consume from |
| `concurrency` | `int` | `1` | Number of concurrent consumers |
| `batchSize` | `int` | `1` | Messages per batch invocation |
| `deadLetterTopic` | `String` | `""` | Topic for failed messages (empty = discard) |
| `adapter` | `String` | `"default"` | Queue adapter: `kafka`, `sqs`, `rabbitmq` |

```java
@FnQueue(topic = "orders.created", concurrency = 4, batchSize = 10)
@FnGroup("order-processing")
public class OrderProcessor implements KubeFnHandler { ... }
```

```java
@FnQueue(topic = "emails.send", deadLetterTopic = "emails.failed", adapter = "sqs")
@FnGroup("notifications")
public class EmailSender implements KubeFnHandler { ... }
```

## @FnLifecyclePhase

Registers a function to run during a specific organism lifecycle phase.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `phase` | `Phase` | (required) | One of `STARTUP`, `READY`, `SHUTDOWN`, `DRAIN` |
| `order` | `int` | `100` | Execution order within the phase (lower = earlier) |

```java
@FnLifecyclePhase(phase = Phase.STARTUP, order = 1)
@FnGroup("infra")
public class CacheWarmer implements KubeFnHandler { ... }
```

```java
@FnLifecyclePhase(phase = Phase.SHUTDOWN, order = 999)
@FnGroup("infra")
public class ConnectionPoolDrainer implements KubeFnHandler { ... }
```

## @Consumes

Declares which heap keys a function reads. Used for dependency graph visualization (`/admin/graph`) and deploy-time validation.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `keys` | `String[]` | (required) | Heap key patterns this function reads |

```java
@Consumes(keys = {"pricing:current", "auth:*"})
public class FraudDetector implements KubeFnHandler { ... }
```

## @Produces

Declares which heap keys a function writes. Used for dependency graph visualization and validation.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `keys` | `String[]` | (required) | Heap key patterns this function publishes |

```java
@Produces(keys = {"fraud:result"})
public class FraudDetector implements KubeFnHandler { ... }
```

## Combined Example

```java
@FnRoute(path = "/api/fraud/check", methods = {"POST"})
@FnGroup("risk")
@Consumes(keys = {"pricing:current", "auth:*"})
@Produces(keys = {"fraud:result"})
public class FraudDetector implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
        // ... compute fraud score ...
        ctx.heap().publish(HeapKeys.FRAUD_RESULT, result, FraudScore.class);
        return KubeFnResponse.ok(result);
    }
}
```
