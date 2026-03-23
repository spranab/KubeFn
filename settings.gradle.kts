rootProject.name = "kubefn"

include(
    "kubefn-api",
    "kubefn-contracts",
    "kubefn-shared",
    "kubefn-testing",
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
    "examples:polyglot-demo",
    "examples:usecases:schedulers",
    "examples:usecases:queue-workers",
    "examples:usecases:webhooks",
    "examples:usecases:sidecar-replacements",
    "examples:usecases:lifecycle",
    "examples:patterns:producer",
    "examples:patterns:consumer",
    "examples:patterns:consumer-with-fallback",
    "examples:patterns:pipeline-orchestrator",
    "examples:patterns:contract-first-stub"
)
