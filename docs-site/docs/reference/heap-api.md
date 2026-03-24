# HeapExchange API Reference

All methods available on `HeapExchange`, accessed via `ctx.heap()`.

## Core Methods (String Keys)

### publish(String key, T value, Class&lt;T&gt; type)

Publishes an object to the heap by reference (zero-copy). Overwrites any existing entry for that key.

**Returns:** `HeapCapsule<T>` -- wrapper with metadata (timestamp, publisher, type).

```java
var result = new PricingResult("USD", 99.99, 0.15, 84.99);
HeapCapsule<PricingResult> capsule = ctx.heap()
    .publish("pricing:current", result, PricingResult.class);
// capsule.publishedAt()  -> Instant
// capsule.publishedBy()  -> "PricingFunction"
// capsule.version()      -> 1
```

### get(String key, Class&lt;T&gt; type)

Reads an object from the heap. Returns `Optional.empty()` if the key does not exist or the type does not match.

**Returns:** `Optional<T>`

```java
Optional<PricingResult> pricing = ctx.heap()
    .get("pricing:current", PricingResult.class);
pricing.ifPresent(p -> log.info("Final price: {}", p.finalPrice()));
```

### contains(String key)

Checks whether a key exists on the heap.

**Returns:** `boolean`

```java
if (ctx.heap().contains("pricing:current")) {
    // safe to read
}
```

### remove(String key)

Removes an object from the heap. Subsequent `get()` calls return `Optional.empty()`.

**Returns:** `boolean` -- `true` if the key existed and was removed.

```java
boolean removed = ctx.heap().remove("pricing:current");
```

### keys()

Returns all keys currently on the heap.

**Returns:** `Set<String>`

```java
Set<String> allKeys = ctx.heap().keys();
// ["pricing:current", "auth:user-42", "fraud:result"]
```

## Typed HeapKey Methods

These methods use `HeapKey<T>` for compile-time type safety. Prefer these over raw string keys.

### publish(HeapKey&lt;T&gt; key, T value)

Type is inferred from the key definition. No `Class<T>` parameter needed.

**Returns:** `HeapCapsule<T>`

```java
HeapCapsule<PricingResult> capsule = ctx.heap()
    .publish(HeapKeys.PRICING_CURRENT, result);
```

### get(HeapKey&lt;T&gt; key)

Type-safe read. No need to pass `Class<T>`.

**Returns:** `Optional<T>`

```java
Optional<PricingResult> pricing = ctx.heap().get(HeapKeys.PRICING_CURRENT);
```

### require(HeapKey&lt;T&gt; key)

Reads the value or throws if absent.

**Returns:** `T`

**Throws:** `HeapKeyMissingException`

```java
PricingResult pricing = ctx.heap().require(HeapKeys.PRICING_CURRENT);
// throws HeapKeyMissingException if not present
```

### getOrDefault(HeapKey&lt;T&gt; key, T defaultValue)

Reads the value or returns the provided default.

**Returns:** `T`

```java
PricingResult pricing = ctx.heap()
    .getOrDefault(HeapKeys.PRICING_CURRENT,
        new PricingResult("USD", 0, 0, 0));
```

## Utility Classes (kubefn-shared)

### HeapReader

```java
// Throws if missing
PricingResult p = HeapReader.require(ctx, HeapKeys.PRICING_CURRENT, PricingResult.class);

// Returns default via supplier
PricingResult p = HeapReader.getOrDefault(ctx, HeapKeys.PRICING_CURRENT,
    PricingResult.class, () -> new PricingResult("USD", 0, 0, 0));
```

### HeapPublisher

```java
// Infers type from the value at runtime
HeapPublisher.publish(ctx, HeapKeys.PRICING_CURRENT, result);
```

## HeapCapsule

Metadata wrapper returned by `publish()`:

| Field | Type | Description |
|---|---|---|
| `value` | `T` | The stored object reference |
| `version` | `long` | Auto-incrementing version number |
| `publishedBy` | `String` | Fully qualified class name of the publisher |
| `publishedAt` | `Instant` | Time of publish |
| `type` | `Class<?>` | Runtime type |

## FakeHeapExchange (Testing)

Full in-memory implementation for unit tests. No runtime needed.

```java
var heap = new FakeHeapExchange();
heap.publish("pricing:current", pricing, PricingResult.class);
Optional<PricingResult> result = heap.get("pricing:current", PricingResult.class);
assertTrue(result.isPresent());
```

## Rules

1. **Never mutate objects read from the heap** -- they are shared references.
2. **Always handle `Optional.empty()`** when using `get()` -- the producer may not have run yet.
3. **Use `HeapKey<T>` over raw strings** for type safety.
4. **Never serialize heap objects** -- they are live JVM references, not messages.
