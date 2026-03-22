package com.example.groovy

import com.kubefn.api.*

@FnRoute(path = "/groovy/demo", methods = ["GET"])
@FnGroup("groovy-showcase")
class GroovyShowcaseFunction implements KubeFnHandler, FnContextAware {
    FnContext ctx

    void setContext(FnContext context) { this.ctx = context }

    KubeFnResponse handle(KubeFnRequest request) throws Exception {
        def log = ctx.logger()
        def startTime = System.nanoTime()

        log.info("GroovyShowcase orchestrator starting")

        // ── Step 1: Invoke ConfigFunction via in-process call ──
        def configStart = System.nanoTime()
        def configFn = ctx.getFunction(ConfigFunction)

        // Build a synthetic request with sample overrides
        def configRequest = KubeFnRequest.builder()
            .method("POST")
            .path("/config/resolve")
            .body("app.name=Groovy Showcase\napp.env=demo\napp.feature_flag=enabled")
            .build()

        def configResponse = configFn.handle(configRequest)
        def configMs = (System.nanoTime() - configStart) / 1_000_000.0

        // ── Step 2: Invoke ScriptRunnerFunction via in-process call ──
        def scriptStart = System.nanoTime()
        def scriptFn = ctx.getFunction(ScriptRunnerFunction)

        def scriptRequest = KubeFnRequest.builder()
            .method("POST")
            .path("/script/run")
            .body('rule("discount") { between(42, 10, 100) ? "eligible" : "ineligible" }')
            .build()

        def scriptResponse = scriptFn.handle(scriptRequest)
        def scriptMs = (System.nanoTime() - scriptStart) / 1_000_000.0

        // ── Step 3: Read HeapExchange results (zero-copy shared state) ──
        def heapStart = System.nanoTime()
        def resolvedConfig = ctx.heap().get("groovy.config.resolved", Map).orElse([:])
        def configSummary = ctx.heap().get("groovy.config.summary", String).orElse("N/A")
        def heapMs = (System.nanoTime() - heapStart) / 1_000_000.0

        def totalMs = (System.nanoTime() - startTime) / 1_000_000.0

        log.info("GroovyShowcase completed in ${totalMs}ms")

        // ── Build combined response with Groovy map literals ──
        def response = [
            showcase: 'Groovy Functions on KubeFn',
            description: 'Demonstrates Groovy closures, GString interpolation, ' +
                         'metaprogramming, and zero-copy HeapExchange across 3 functions',
            steps: [
                [
                    step  : 1,
                    name  : 'ConfigFunction',
                    route : 'POST /config/resolve',
                    result: 'Resolved configuration with fallback chains'
                ],
                [
                    step  : 2,
                    name  : 'ScriptRunnerFunction',
                    route : 'POST /script/run',
                    result: 'Evaluated business rule expression'
                ],
                [
                    step  : 3,
                    name  : 'HeapExchange read',
                    route : 'in-memory (zero-copy)',
                    result: "Read ${resolvedConfig.size()} config entries from shared heap"
                ]
            ],
            heap_snapshot: [
                config_summary: configSummary,
                config_keys   : resolvedConfig.keySet() as List
            ],
            group   : ctx.groupName(),
            revision: ctx.revisionId(),
            _meta   : [
                total_ms       : totalMs,
                config_call_ms : configMs,
                script_call_ms : scriptMs,
                heap_read_ms   : heapMs,
                note           : 'All calls are in-process; no network hops'
            ]
        ]

        KubeFnResponse.ok(response)
    }
}
