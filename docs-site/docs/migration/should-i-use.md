# Should I Use KubeFn?

Brutally honest. KubeFn is not for everyone.

## Best For

- **High-chatter internal pipelines** — you have 5+ services calling each other per request (checkout -> auth -> pricing -> tax -> inventory -> fraud). KubeFn eliminates the serialization and network round-trips between them.
- **Independent deployment without network tax** — teams that want per-function deploys but hate the latency of HTTP between co-located services.
- **Cron/queue worker consolidation** — dozens of small scheduled jobs or queue consumers that each run in their own pod today. KubeFn runs them all in one organism, zero pod boot overhead.
- **Shared cache/pool scenarios** — services that all need the same connection pool, in-memory cache, or computed state. HeapExchange gives you zero-copy sharing without Redis.

## Not For

- **Multi-tenant SaaS with untrusted code** — functions share a JVM heap. A malicious function can read any heap key. There is no tenant isolation boundary.
- **Teams needing hard process isolation** — a single function OOM or thread leak kills the entire organism. If one bad deploy must not affect others, use separate pods.
- **Polyglot teams** — KubeFn is JVM-first (Java, Kotlin, Scala, Groovy). Python and Node runtimes are beta, run out-of-process via gRPC, and lose the zero-copy benefit.
- **Very simple 1-2 service architectures** — if you have two services and a database, the operational overhead of KubeFn is not worth it. Use Spring Boot or Quarkus.

## Comparison

| | KubeFn | Monolith | Microservices | FaaS (Lambda) |
|---|---|---|---|---|
| **Deployment independence** | Per-function JAR | None (redeploy all) | Per-service | Per-function |
| **Inter-service latency** | ~0.05ms (heap ref) | 0 (method call) | 1-10ms (HTTP/gRPC) | 5-50ms (HTTP) |
| **Shared state** | Zero-copy heap | Shared memory | Redis / HTTP | DynamoDB / S3 |
| **Isolation** | Classloader only | None | Full process | Full container |
| **Cold start** | ~0ms (hot reload) | N/A | N/A | 100ms-10s |
| **Infra cost (10 services)** | 1-2 pods | 1 pod | 10+ pods | Pay-per-invoke |

## Current Limitations

- **No distributed transactions** — HeapExchange is a shared-memory store, not a transaction coordinator. Use sagas or eventual consistency.
- **Python/Node are beta** — they run as sidecar processes with gRPC bridging. You lose zero-copy semantics.
- **No formal security audit** — KubeFn has not been audited by a third party. Do not use it to run untrusted code.
- **Single-organism memory = shared failure domain** — if one function leaks memory, all functions in the organism are affected. Monitor with `kubefn_jvm_heap_used_mb`.

## When to Choose KubeFn

You have **5+ JVM services that form a pipeline** and the serialization overhead matters. You are willing to trade process isolation for sub-millisecond inter-function calls. Your team deploys frequently and wants per-function rollouts without the cost of per-service pods.

If that is not you, microservices or a well-structured monolith are the right choice.
