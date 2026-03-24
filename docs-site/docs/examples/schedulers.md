# Cron Replacement

Replace standalone Kubernetes CronJobs with `@FnSchedule` functions that run inside the organism. No pod boot latency, full HeapExchange access, and built-in monitoring via `/admin/scheduler`.

## Nightly Revenue Report

A scheduled function that aggregates order data and publishes a summary to the heap for the dashboard API to read.

```java
@FnSchedule(cron = "0 0 2 * * *", timezone = "America/New_York", timeoutMs = 600000)
@FnGroup("reports")
@Produces(keys = {"report:daily-revenue"})
public class DailyRevenueReport implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var db = ctx.config().get("datasource.url");
        var orders = OrderRepository.fetchSince(db, Instant.now().minus(Duration.ofDays(1)));

        double total = orders.stream().mapToDouble(Order::amount).sum();
        var report = new RevenueReport(LocalDate.now(), total, orders.size());
        ctx.heap().publish(HeapKeys.dailyRevenue(LocalDate.now()), report, RevenueReport.class);

        return KubeFnResponse.ok(Map.of("total", total, "orders", orders.size()));
    }
}
```

## Periodic Cache Warmer

Refreshes pricing data on the heap every 5 minutes so API functions always read fresh data with zero latency.

```java
@FnSchedule(cron = "0 */5 * * * *", runOnStart = true, skipIfRunning = true)
@FnGroup("infra")
@Produces(keys = {"pricing:current"})
public class PricingCacheWarmer implements KubeFnHandler, FnContextAware {
    private FnContext ctx;
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var pricing = ExternalPricingApi.fetchCurrent();
        ctx.heap().publish(HeapKeys.PRICING_CURRENT, pricing, PricingResult.class);
        return KubeFnResponse.ok("refreshed");
    }
}
```

## HeapExchange Flow

1. Scheduler triggers the function on the cron schedule (no pod boot -- already loaded)
2. Function fetches data from an external source (database, API)
3. Function publishes results to the heap
4. Other functions (dashboard APIs, checkout pipeline) read the cached data zero-copy

## Test and Deploy

```java
@Test
void revenueReportPublishesToHeap() {
    var heap = new FakeHeapExchange();
    var ctx = FnContext.withHeap(heap);
    var fn = new DailyRevenueReport();
    fn.setContext(ctx);

    fn.handle(KubeFnRequest.empty());

    var report = heap.get(HeapKeys.dailyRevenue(LocalDate.now()), RevenueReport.class);
    assertTrue(report.isPresent());
    assertTrue(report.get().total() >= 0);
}
```

```bash
kubefn deploy --jar build/libs/reports.jar --group reports
kubefn scheduler   # verify next run times
```

## CronJob vs @FnSchedule

| | K8s CronJob | KubeFn @FnSchedule |
|---|---|---|
| Startup time | 5-30s (pod boot) | 0ms (already loaded) |
| Heap access | None | Full HeapExchange |
| Deploy | Separate YAML | Same JAR as functions |
| Monitoring | `kubectl logs` | `/admin/scheduler` |
| Skip-if-running | `concurrencyPolicy` | `skipIfRunning = true` |
