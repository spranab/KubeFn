package com.example.groovy

import com.kubefn.api.*

@FnRoute(path = "/config/resolve", methods = ["POST"])
@FnGroup("groovy-showcase")
class ConfigFunction implements KubeFnHandler, FnContextAware {
    FnContext ctx

    void setContext(FnContext context) { this.ctx = context }

    KubeFnResponse handle(KubeFnRequest request) throws Exception {
        def log = ctx.logger()
        def body = request.bodyAsString()

        // Groovy collection literals for default configuration
        def defaults = [
            'app.name'      : 'KubeFn Groovy Demo',
            'app.version'   : '1.0.0',
            'app.timeout_ms': '5000',
            'app.retries'   : '3',
            'app.env'       : 'development'
        ]

        // Parse incoming overrides from request body
        def overrides = parseOverrides(body)

        // Resolve configuration with fallback chain using Groovy closures
        def resolveWithFallback = { String key ->
            def chain = [overrides, envFallback(), defaults]
            chain.findResult { source -> source[key] } ?: "UNRESOLVED(${key})"
        }

        // Build resolved config using Groovy's collect and builder pattern
        def requestedKeys = overrides.keySet() + defaults.keySet()
        def resolved = requestedKeys.collectEntries { key ->
            [(key): resolveWithFallback(key)]
        }

        // GString interpolation for summary
        def summary = "Resolved ${resolved.size()} config keys " +
                "(${overrides.size()} overrides, ${defaults.size()} defaults)"

        log.info(summary)

        // Publish resolved config to HeapExchange for sibling functions
        ctx.heap().publish("groovy.config.resolved", resolved, Map)
        ctx.heap().publish("groovy.config.summary", summary, String)

        // Build response using Groovy map literals
        def response = [
            status  : 'resolved',
            summary : summary,
            config  : resolved,
            sources : [
                defaults_count : defaults.size(),
                overrides_count: overrides.size()
            ],
            group   : ctx.groupName(),
            revision: ctx.revisionId()
        ]

        KubeFnResponse.ok(response)
    }

    /**
     * Parse key=value pairs from the request body, one per line.
     */
    private Map<String, String> parseOverrides(String body) {
        if (!body?.trim()) return [:]

        body.trim().readLines()
            .findAll { it.contains('=') }
            .collectEntries { line ->
                def parts = line.split('=', 2)
                [(parts[0].trim()): parts[1].trim()]
            }
    }

    /**
     * Simulate environment variable fallback using a closure.
     */
    private Map<String, String> envFallback() {
        // In real usage this would read from System.getenv() or a config service.
        // Using a static map here to keep the example self-contained.
        [
            'app.env'       : 'staging',
            'app.timeout_ms': '3000'
        ]
    }
}
