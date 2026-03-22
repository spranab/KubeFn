package com.example.groovy

import com.kubefn.api.*

@FnRoute(path = "/script/run", methods = ["POST"])
@FnGroup("groovy-showcase")
class ScriptRunnerFunction implements KubeFnHandler, FnContextAware {
    FnContext ctx

    void setContext(FnContext context) { this.ctx = context }

    KubeFnResponse handle(KubeFnRequest request) throws Exception {
        def log = ctx.logger()
        def body = request.bodyAsString()

        if (!body?.trim()) {
            return KubeFnResponse.status(400).body([
                error  : 'Missing request body',
                hint   : 'POST a JSON-like expression or a simple business rule expression',
                example: 'quantity > 100 ? "bulk" : "standard"'
            ])
        }

        // Read config published by ConfigFunction via HeapExchange (zero-copy)
        def configOpt = ctx.heap().get("groovy.config.resolved", Map)
        def config = configOpt.orElse([:])

        log.info("ScriptRunner executing with ${config.size()} config entries from HeapExchange")

        // Build a sandboxed binding with config values and utility closures
        def binding = buildBinding(config)

        // Evaluate the expression using Groovy's metaprogramming
        def result = evaluateExpression(body.trim(), binding)

        def response = [
            expression    : body.trim(),
            result        : result.value,
            result_type   : result.type,
            config_loaded : config.size(),
            bindings_used : binding.variables.keySet() as List,
            group         : ctx.groupName(),
            revision      : ctx.revisionId()
        ]

        KubeFnResponse.ok(response)
    }

    /**
     * Build a Groovy Binding populated with config values and utility closures.
     * Demonstrates Groovy metaprogramming: closures as first-class citizens.
     */
    private Binding buildBinding(Map<String, String> config) {
        def binding = new Binding()

        // Expose config values as binding variables
        config.each { key, value ->
            def varName = key.replaceAll(/[.\-]/, '_')
            binding.setVariable(varName, value)
        }

        // Inject utility closures that scripts can call
        binding.setVariable('clamp', { Number val, Number lo, Number hi ->
            Math.max(lo.doubleValue(), Math.min(hi.doubleValue(), val.doubleValue()))
        })

        binding.setVariable('between', { Number val, Number lo, Number hi ->
            val.doubleValue() >= lo.doubleValue() && val.doubleValue() <= hi.doubleValue()
        })

        binding.setVariable('coalesce', { Object... args ->
            args.find { it != null }
        })

        // Expose a simple rule DSL via metaprogramming
        binding.setVariable('rule', { String name, Closure body ->
            [name: name, result: body.call()]
        })

        binding
    }

    /**
     * Safely evaluate a Groovy expression string.
     * In a production system you would use a CompilerConfiguration with
     * SecureASTCustomizer to restrict allowed syntax.
     */
    private Map evaluateExpression(String expression, Binding binding) {
        try {
            def shell = new GroovyShell(binding)
            def result = shell.evaluate(expression)
            [value: result?.toString() ?: 'null', type: result?.getClass()?.simpleName ?: 'null']
        } catch (Exception e) {
            [value: "ERROR: ${e.message}", type: 'Error']
        }
    }
}
