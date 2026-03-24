# Migrating from Spring Boot

KubeFn replaces the network between your Spring services with shared-memory heap reads. Your business logic stays the same.

## Conceptual Mapping

| Spring Boot | KubeFn | Notes |
|---|---|---|
| `@RestController` | `KubeFnHandler` + `@FnRoute` | One handler per endpoint |
| `RestTemplate` / `WebClient` call | `ctx.heap().get(key, Type.class)` | Zero-copy, no network |
| `@Service` bean | Same class, used directly in function | No change needed |
| `@Scheduled(cron = "...")` | `@FnSchedule(cron = "...")` | Built-in, no Spring context |
| `application.properties` | `ctx.config().get("key")` | Loaded from ConfigMap or env |
| `@Autowired` dependency | Constructor or `ctx.getFunction()` | No DI container |
| API client library | Contract type + `HeapKey<T>` | Shared types replace client JARs |

## Step 1: Identify Your Hot Pipeline

Find the 3-7 services that call each other most frequently per request:

```
API Gateway -> Auth -> Pricing -> Tax -> Inventory -> Fraud -> Order
```

These are your first KubeFn organism. Everything else stays as Spring Boot.

## Step 2: Create kubefn-contracts

Define shared types for every object that crosses service boundaries:

```java
// kubefn-contracts/src/main/java/com/kubefn/contracts/types/PricingResult.java
public record PricingResult(String currency, double basePrice, double discount, double finalPrice) {}
```

```java
// kubefn-contracts/src/main/java/com/kubefn/contracts/HeapKeys.java
public static final HeapKey<PricingResult> PRICING_CURRENT =
    HeapKey.of("pricing:current", PricingResult.class);
```

## Step 3: Convert Each Service Endpoint

Before (Spring Boot):

```java
@RestController
public class PricingController {
    @PostMapping("/api/pricing")
    public PricingResult calculate(@RequestBody PricingRequest req) {
        double finalPrice = req.unitPrice() * req.quantity() * (1 - calculateDiscount(req));
        return new PricingResult("USD", req.unitPrice(), 0.15, finalPrice);
    }
}
```

After (KubeFn):

```java
@FnRoute(path = "/api/pricing", methods = {"POST"})
@FnGroup("checkout")
@Produces(keys = {"pricing:current"})
public class PricingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var req = request.bodyAs(PricingRequest.class);
        double finalPrice = req.unitPrice() * req.quantity() * (1 - calculateDiscount(req));
        var result = new PricingResult("USD", req.unitPrice(), 0.15, finalPrice);
        ctx.heap().publish(HeapKeys.PRICING_CURRENT, result, PricingResult.class);
        return KubeFnResponse.ok(result);
    }
}
```

## Step 4: Replace RestTemplate Calls with HeapExchange

Before:

```java
PricingResult pricing = restTemplate.postForObject(
    "http://pricing-service/api/pricing", body, PricingResult.class);
TaxResult tax = restTemplate.postForObject(
    "http://tax-service/api/tax", pricing, TaxResult.class);
```

After:

```java
ctx.getFunction(PricingFunction.class).handle(request);
ctx.getFunction(TaxFunction.class).handle(request);

PricingResult pricing = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);
TaxCalculation tax = HeapReader.require(ctx, HeapKeys.TAX_CALCULATED, TaxCalculation.class);
```

## Step 5: Deploy Alongside Existing Services

Route only the hot pipeline through KubeFn. Everything else stays on Spring Boot:

```yaml
# Ingress rules
- path: /api/checkout
  backend: kubefn-organism:8080    # Hot pipeline on KubeFn
- path: /api/users
  backend: user-service:8080       # Stays on Spring Boot
```

## What Stays the Same

- Java, Gradle/Maven, JUnit, Mockito
- Your business logic classes (`PricingCalculator`, `FraudRules`, etc.)
- Database access code (JPA, JDBC, jOOQ)
- External API clients (Stripe, Twilio, etc.)

## What Changes

- No HTTP between pipeline steps -- `HeapKey<T>` instead of API clients
- No Spring context boot time -- functions load in milliseconds, not seconds
- No `@Autowired` -- use `ctx.getFunction()` for sibling functions
- One organism pod replaces N service pods for the hot pipeline
- JARs are thin (KB not MB) -- the runtime provides the platform layer
