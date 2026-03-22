# KubeFn — Live Application Fabric

> **Deploy like microservices. Perform like a monolith. Debug like neither could.**

KubeFn is a Kubernetes-native runtime where independently deployable Java functions share a living memory space. Functions exchange objects at heap speed — **zero serialization, zero network hops, same memory address**.

**131x faster** than equivalent microservices for a 7-function checkout pipeline.

```
Microservices:  ~60ms  (7 HTTP calls + JSON serialization)
KubeFn:         0.458ms (7 in-memory compositions, zero-copy)
```

## What is Memory-Continuous Architecture?

Today's architectures force a false choice:

| | Shared Memory | Independent Deploy | Hot-Swap |
|---|---|---|---|
| **Monolith** | Yes | No | No |
| **Microservices** | No | Yes | Rolling restart |
| **FaaS** | No | Yes | Cold start |
| **KubeFn** | **Yes** | **Yes** | **Yes** |

KubeFn breaks the triangle. Functions are independently deployable but collaborate through shared heap objects at nanosecond speed.

## Architecture

```
┌─── Kubernetes Namespace ─────────────────────────────────────┐
│                                                               │
│  ┌─── KubeFn Runtime (Living JVM Organism) ───────────────┐  │
│  │                                                         │  │
│  │  ┌─────────────────────────────────────────────────┐   │  │
│  │  │         HeapExchange (Shared Object Graph)       │   │  │
│  │  │  ┌─────────┐  ┌──────────┐  ┌──────────────┐   │   │  │
│  │  │  │ catalog  │  │ session  │  │ risk-scores  │   │   │  │
│  │  │  │ (parsed) │  │ (live)   │  │ (computed)   │   │   │  │
│  │  │  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │   │  │
│  │  └───────┼──────────────┼───────────────┼──────────┘   │  │
│  │          │  zero-copy   │   zero-copy   │               │  │
│  │  ┌───── Function Groups (Classloader Isolation) ───┐   │  │
│  │  │  Group A             Group B                     │  │  │
│  │  │  ┌──────┐ ┌──────┐  ┌──────┐ ┌───────┐         │  │  │
│  │  │  │/parse│→│/price│  │/fraud│→│/decide│         │  │  │
│  │  │  └──────┘ └──────┘  └──────┘ └───────┘         │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  │                                                         │  │
│  │  Shared: JIT warmth · thread pools · connection pools   │  │
│  │  Netty HTTP (:8080)  · Admin (:8081)  · Virtual Threads │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  CRDs: KubeFnGroup (kfg) · KubeFnFunction (kff)              │
└───────────────────────────────────────────────────────────────┘
```

## Quick Start (5 minutes)

### Prerequisites
- Java 21+
- Docker
- Kubernetes cluster (or minikube/kind)

### 1. Clone and build

```bash
git clone https://github.com/spranab/KubeFn.git
cd KubeFn
./gradlew build
./gradlew :kubefn-runtime:shadowJar :examples:checkout-pipeline:jar
```

### 2. Run locally

```bash
# Create functions directory with example
mkdir -p /tmp/kubefn-functions/checkout-pipeline
cp examples/checkout-pipeline/build/libs/checkout-pipeline-*.jar \
   /tmp/kubefn-functions/checkout-pipeline/

# Start the organism
KUBEFN_FUNCTIONS_DIR=/tmp/kubefn-functions \
  java --enable-preview -jar kubefn-runtime/build/libs/kubefn-runtime-*-all.jar
```

### 3. Test it

```bash
# Health check
curl localhost:8081/healthz
# {"status":"alive","organism":"kubefn"}

# List deployed functions
curl localhost:8081/admin/functions

# Run the 7-function checkout pipeline
curl "localhost:8080/checkout/quote?userId=user-001"
```

The response includes timing metadata:
```json
{
  "auth": { "userId": "user-001", "tier": "premium" },
  "pricing": { "finalPrice": 84.99, "discount": 0.15 },
  "tax": { "total": 92.00 },
  "fraudCheck": { "approved": true, "riskScore": 0.05 },
  "_meta": {
    "pipelineSteps": 7,
    "totalTimeMs": "0.458",
    "zeroCopy": true,
    "note": "7 functions composed in-memory. No HTTP calls. No serialization."
  }
}
```

## The Revolutionary Primitives

### HeapExchange — Zero-Copy Shared Object Graph

Functions publish and consume typed objects directly from the JVM heap. No serialization. No network. Same memory address.

```java
// Function A: parse a payload ONCE
ctx.heap().publish("catalog", parsedCatalog, ProductCatalog.class);

// Function B: read the SAME object — zero copy
ProductCatalog catalog = ctx.heap().get("catalog", ProductCatalog.class)
    .orElseThrow();
// This IS the same object in memory. Not a copy. Not deserialized.
```

### Born-Warm Deploys

New function revisions enter an already-hot JVM. Shared libraries (Jackson, Netty, etc.) are already JIT-compiled. A new deploy reaches peak performance in **< 1 second**, not 30+ seconds.

### Hot-Swap Without Restart

Replace individual functions while traffic flows. Old classloader is discarded, new one is created. Zero dropped requests. The organism lives — only the organ is replaced.

### Per-Group Resource Governance

Each function group has semaphore-based concurrency limits, preventing noisy neighbors from degrading the organism.

## Writing a Function

```java
@FnRoute(path = "/greet", methods = {"GET"})
@FnGroup("my-service")
public class GreetFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) {
        String name = request.queryParam("name").orElse("World");

        // Publish to HeapExchange — other functions read this zero-copy
        ctx.heap().publish("greeting", Map.of("message", "Hello, " + name), Map.class);

        return KubeFnResponse.ok(Map.of("message", "Hello, " + name));
    }
}
```

## Deploy to Kubernetes

```bash
# Apply CRDs
kubectl apply -f deploy/crds/

# Create namespace and deploy
kubectl apply -f deploy/manifests/namespace.yaml
kubectl apply -f deploy/manifests/runtime-deployment.yaml

# Check status
kubectl get kfg -n kubefn    # KubeFnGroups
kubectl get kff -n kubefn    # KubeFnFunctions
```

## Project Structure

```
KubeFn/
├── kubefn-api/          # Function author contract (12 types, zero heavy deps)
├── kubefn-runtime/      # The living organism (Netty, HeapExchange, classloading)
├── kubefn-operator/     # K8s CRDs and operator
├── kubefn-sdk/          # Local dev server
├── examples/
│   ├── hello-function/        # Basic HeapExchange demo
│   └── checkout-pipeline/     # 7-function benchmark (the 131x demo)
└── deploy/              # K8s manifests, CRDs, Dockerfile
```

## Benchmark

7-function checkout pipeline: Auth → Inventory → Pricing → Shipping → Tax → FraudCheck → Assemble

| Platform | Latency | Serialization Cycles | HTTP Calls |
|---|---|---|---|
| Microservices | ~60ms | 7 | 7 |
| KubeFn (local) | **0.458ms** | **0** | **0** |
| KubeFn (K8s) | **1.489ms** | **0** | **0** |

All functions share heap objects via HeapExchange. Zero serialization. Zero network hops.

## What KubeFn is NOT

- **Not multi-tenant FaaS** — functions must be from the same trust boundary (same team/app)
- **Not a service mesh** — there's no network between functions, they share memory
- **Not an app server** — functions deploy independently with their own revisions
- **Not polyglot (yet)** — Java/Kotlin first, designed for JVM

## What KubeFn is BEST for

- Latency-sensitive business pipelines (checkout, pricing, fraud)
- High-chatter service graphs where serialization dominates latency
- AI/ML inference pipelines where context objects are large and shared
- Modular monolith decomposition where services need sub-millisecond communication
- Event processing where stages share state

## Roadmap

| Version | Theme | Key Features |
|---|---|---|
| **v0.1** (current) | Speed proof | HeapExchange, FnGraph, born-warm, hot-swap, CRDs |
| **v0.2** | Production trust | Revision-pinned execution, audit log, circuit breakers, CLI |
| **v0.3** | Category definer | Causal state introspection, deterministic replay, AI agent demo |
| **v1.0** | The movement | Time-travel debugging, YantrikDB integration, Cognitive App Stack |

## Tech Stack

- **Java 21** with virtual threads and `StructuredTaskScope`
- **Netty** for async HTTP (event loops for IO, virtual threads for functions)
- **Caffeine** for per-group revision-scoped caching
- **Fabric8/JOSDK** for Kubernetes operator
- **Gradle Kotlin DSL** for builds

## Contributing

KubeFn is in early development. We welcome contributions, feedback, and ideas.

## License

MIT

---

*Built by [Pranab Sarkar](https://pranab.co.in) — Senior Software Developer, AI Researcher, and builder of [YantrikDB](https://yantrikdb.com), [brainstorm-mcp](https://github.com/spranab/brainstorm-mcp), and [saga-mcp](https://github.com/spranab/saga-mcp).*

**Where functions share memory, not messages.**
