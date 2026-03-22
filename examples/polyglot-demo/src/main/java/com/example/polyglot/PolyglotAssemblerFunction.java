package com.example.polyglot;

import com.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Polyglot Proof: reads objects from HeapExchange that were published
 * by functions written in Java, Kotlin, Scala, and Groovy.
 *
 * <p>All four languages compile to JVM bytecode and share the same heap.
 * Zero serialization. Zero copies. Same memory address.
 *
 * <p>This proves Memory-Continuous Architecture works across JVM languages.
 */
@FnRoute(path = "/polyglot/proof", methods = {"GET"})
@FnGroup("polyglot-demo")
public class PolyglotAssemblerFunction implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        Map<String, Object> result = new LinkedHashMap<>();

        // Read from Java function (if present on heap)
        var javaData = ctx.heap().get("java:greeting", Map.class);
        result.put("java", javaData.orElse(Map.of("status", "not_loaded",
                "hint", "Deploy hello-function group")));

        // Read from Kotlin function (if present on heap)
        var kotlinData = ctx.heap().get("kotlin:recommendations", Map.class);
        result.put("kotlin", kotlinData.orElse(Map.of("status", "not_loaded",
                "hint", "Deploy kotlin-functions group")));

        // Read from Scala function (if present on heap)
        var scalaData = ctx.heap().get("scala:stream_results", Map.class);
        result.put("scala", scalaData.orElse(Map.of("status", "not_loaded",
                "hint", "Deploy scala-functions group")));

        // Read from Groovy function (if present on heap)
        var groovyData = ctx.heap().get("groovy:config", Map.class);
        result.put("groovy", groovyData.orElse(Map.of("status", "not_loaded",
                "hint", "Deploy groovy-functions group")));

        long durationNanos = System.nanoTime() - startNanos;
        int loaded = 0;
        if (javaData.isPresent()) loaded++;
        if (kotlinData.isPresent()) loaded++;
        if (scalaData.isPresent()) loaded++;
        if (groovyData.isPresent()) loaded++;

        result.put("_meta", Map.of(
                "proof", "Memory-Continuous Architecture works across JVM languages",
                "languages", Map.of(
                        "java", javaData.isPresent(),
                        "kotlin", kotlinData.isPresent(),
                        "scala", scalaData.isPresent(),
                        "groovy", groovyData.isPresent()
                ),
                "loadedLanguages", loaded,
                "totalLanguages", 4,
                "zeroCopy", true,
                "readTimeNanos", durationNanos,
                "readTimeMs", String.format("%.3f", durationNanos / 1_000_000.0),
                "note", "All objects read directly from JVM heap — no serialization across languages"
        ));

        return KubeFnResponse.ok(result);
    }
}
