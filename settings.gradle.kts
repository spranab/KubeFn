rootProject.name = "kubefn"

include(
    "kubefn-api",
    "kubefn-runtime",
    "kubefn-operator",
    "kubefn-sdk",

    // Examples
    "examples:hello-function",
    "examples:checkout-pipeline",

    // Enterprise examples (10 production-grade pipelines)
    "examples:fraud-detection",
    "examples:ai-inference-pipeline",
    "examples:realtime-pricing",
    "examples:payment-processing",
    "examples:notification-orchestrator",
    "examples:search-recommendation",
    "examples:api-gateway",
    "examples:workflow-saga",
    "examples:event-processing",
    "examples:clinical-alerts",

    // Multi-language examples (JVM polyglot — same heap, zero-copy)
    "examples:kotlin-functions",
    "examples:scala-functions",
    "examples:groovy-functions",
    "examples:polyglot-demo"
)
