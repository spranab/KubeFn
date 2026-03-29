package com.kubefn.runtime.graph;

import com.kubefn.runtime.heap.HeapTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Heap Dependency Graph — builds a DAG of function data dependencies
 * from observed HeapTrace operations.
 *
 * <p>If PricingFunction publishes "pricing:current" and TaxFunction reads it,
 * the graph has an edge: PricingFunction → TaxFunction via "pricing:current".
 *
 * <p>This enables:
 * <ul>
 *   <li>{@code kubefn graph} — visualize the dependency DAG</li>
 *   <li>{@code kubefn deploy --graph checkout} — deploy producers before consumers</li>
 *   <li>Impact analysis — "deploying PricingFunction affects 4 consumers"</li>
 *   <li>Cycle detection — find circular heap dependencies</li>
 * </ul>
 *
 * <p>Built entirely from runtime observation. No static analysis.
 * More accurate because it captures actual production access patterns.
 */
public class HeapDependencyGraph {
    private static final Logger log = LoggerFactory.getLogger(HeapDependencyGraph.class);

    /** function → set of heap keys it PUBLISHES */
    private final Map<String, Set<String>> producers = new LinkedHashMap<>();

    /** function → set of heap keys it READS */
    private final Map<String, Set<String>> consumers = new LinkedHashMap<>();

    /** heap key → producer function */
    private final Map<String, String> keyProducer = new LinkedHashMap<>();

    /** heap key → set of consumer functions */
    private final Map<String, Set<String>> keyConsumers = new LinkedHashMap<>();

    /**
     * Build the graph from HeapTrace data.
     */
    public static HeapDependencyGraph buildFrom(HeapTrace trace) {
        var graph = new HeapDependencyGraph();
        var entries = trace.recent(10_000); // scan all available

        for (var entry : entries) {
            String fn = entry.group() + "." + entry.function();
            String key = entry.key();

            switch (entry.operation()) {
                case PUBLISH -> {
                    graph.producers.computeIfAbsent(fn, k -> new LinkedHashSet<>()).add(key);
                    graph.keyProducer.put(key, fn);
                }
                case GET_HIT, GET_MISS -> {
                    graph.consumers.computeIfAbsent(fn, k -> new LinkedHashSet<>()).add(key);
                    graph.keyConsumers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(fn);
                }
                case REMOVE -> {} // ignored for dependency graph
            }
        }

        return graph;
    }

    /**
     * Get all edges: producer → consumer via key.
     */
    public List<Edge> edges() {
        var edges = new ArrayList<Edge>();
        for (var entry : keyProducer.entrySet()) {
            String key = entry.getKey();
            String producer = entry.getValue();
            Set<String> cons = keyConsumers.getOrDefault(key, Set.of());
            for (String consumer : cons) {
                if (!producer.equals(consumer)) {
                    edges.add(new Edge(producer, consumer, key));
                }
            }
        }
        return edges;
    }

    /**
     * Get all functions (nodes) in the graph.
     */
    public Set<String> functions() {
        var all = new LinkedHashSet<String>();
        all.addAll(producers.keySet());
        all.addAll(consumers.keySet());
        return all;
    }

    /**
     * Get the subgraph reachable from a given function (downstream impact).
     */
    public Set<String> downstream(String functionName) {
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(functionName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            // Find keys this function publishes
            Set<String> publishedKeys = producers.getOrDefault(current, Set.of());
            for (String key : publishedKeys) {
                // Find consumers of those keys
                Set<String> cons = keyConsumers.getOrDefault(key, Set.of());
                for (String consumer : cons) {
                    if (!visited.contains(consumer)) {
                        queue.add(consumer);
                    }
                }
            }
        }

        return visited;
    }

    /**
     * Get the subgraph required by a given function (upstream dependencies).
     */
    public Set<String> upstream(String functionName) {
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(functionName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            // Find keys this function reads
            Set<String> readKeys = consumers.getOrDefault(current, Set.of());
            for (String key : readKeys) {
                // Find producer of that key
                String producer = keyProducer.get(key);
                if (producer != null && !visited.contains(producer)) {
                    queue.add(producer);
                }
            }
        }

        return visited;
    }

    /**
     * Topological sort — producers before consumers.
     * Returns null if there's a cycle.
     */
    public List<String> topologicalSort() {
        // Build adjacency list: producer → consumers
        var adj = new LinkedHashMap<String, Set<String>>();
        var inDegree = new LinkedHashMap<String, Integer>();

        for (String fn : functions()) {
            adj.putIfAbsent(fn, new LinkedHashSet<>());
            inDegree.putIfAbsent(fn, 0);
        }

        for (var edge : edges()) {
            adj.computeIfAbsent(edge.from, k -> new LinkedHashSet<>()).add(edge.to);
            inDegree.merge(edge.to, 1, Integer::sum);
            inDegree.putIfAbsent(edge.from, 0);
        }

        // Kahn's algorithm
        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var sorted = new ArrayList<String>();
        while (!queue.isEmpty()) {
            String fn = queue.poll();
            sorted.add(fn);
            for (String neighbor : adj.getOrDefault(fn, Set.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (sorted.size() != functions().size()) {
            log.warn("Cycle detected in heap dependency graph! Sorted {} of {} functions.",
                    sorted.size(), functions().size());
            return null; // cycle
        }

        return sorted;
    }

    /**
     * Detect cycles in the dependency graph.
     */
    public List<List<String>> detectCycles() {
        var cycles = new ArrayList<List<String>>();
        var visited = new HashSet<String>();
        var recursionStack = new LinkedHashSet<String>();

        for (String fn : functions()) {
            if (!visited.contains(fn)) {
                detectCyclesDFS(fn, visited, recursionStack, cycles);
            }
        }

        return cycles;
    }

    private void detectCyclesDFS(String fn, Set<String> visited,
                                  LinkedHashSet<String> stack, List<List<String>> cycles) {
        visited.add(fn);
        stack.add(fn);

        Set<String> publishedKeys = producers.getOrDefault(fn, Set.of());
        for (String key : publishedKeys) {
            for (String consumer : keyConsumers.getOrDefault(key, Set.of())) {
                if (!visited.contains(consumer)) {
                    detectCyclesDFS(consumer, visited, stack, cycles);
                } else if (stack.contains(consumer)) {
                    // Found a cycle — extract it
                    var cycle = new ArrayList<String>();
                    boolean inCycle = false;
                    for (String s : stack) {
                        if (s.equals(consumer)) inCycle = true;
                        if (inCycle) cycle.add(s);
                    }
                    cycle.add(consumer); // close the cycle
                    cycles.add(cycle);
                }
            }
        }

        stack.remove(fn);
    }

    /**
     * Impact analysis: what's affected if we deploy a function?
     */
    public Map<String, Object> impactAnalysis(String functionName) {
        var down = downstream(functionName);
        down.remove(functionName); // exclude self
        var up = upstream(functionName);
        up.remove(functionName);

        Set<String> publishedKeys = producers.getOrDefault(functionName, Set.of());
        var affectedConsumers = new LinkedHashMap<String, List<String>>();
        for (String key : publishedKeys) {
            var cons = keyConsumers.getOrDefault(key, Set.of()).stream()
                    .filter(c -> !c.equals(functionName))
                    .toList();
            if (!cons.isEmpty()) {
                affectedConsumers.put(key, cons);
            }
        }

        return Map.of(
                "function", functionName,
                "publishesKeys", producers.getOrDefault(functionName, Set.of()),
                "readsKeys", consumers.getOrDefault(functionName, Set.of()),
                "downstreamFunctions", down,
                "upstreamFunctions", up,
                "affectedConsumers", affectedConsumers,
                "totalImpact", down.size()
        );
    }

    /**
     * Render as ASCII for CLI.
     */
    public String renderAscii() {
        var sb = new StringBuilder();
        var sorted = topologicalSort();
        var edgeList = edges();

        if (sorted == null) {
            sb.append("  WARNING: Cycle detected in dependency graph!\n\n");
            sorted = new ArrayList<>(functions());
        }

        sb.append("  Heap Dependency Graph (").append(functions().size())
                .append(" functions, ").append(edgeList.size()).append(" edges)\n\n");

        // Render each function with its connections
        for (String fn : sorted) {
            Set<String> publishes = producers.getOrDefault(fn, Set.of());
            Set<String> reads = consumers.getOrDefault(fn, Set.of());

            sb.append("  ┌─ ").append(fn).append("\n");
            if (!publishes.isEmpty()) {
                sb.append("  │  publishes: ").append(String.join(", ", publishes)).append("\n");
            }
            if (!reads.isEmpty()) {
                sb.append("  │  reads:     ").append(String.join(", ", reads)).append("\n");
            }

            // Show downstream edges
            for (var edge : edgeList) {
                if (edge.from.equals(fn)) {
                    sb.append("  │  └──→ ").append(edge.to)
                            .append(" (via ").append(edge.key).append(")\n");
                }
            }
            sb.append("  │\n");
        }

        return sb.toString();
    }

    /**
     * Convert to JSON-compatible map.
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "functions", functions(),
                "edges", edges().stream().map(e -> Map.of(
                        "from", e.from, "to", e.to, "key", e.key)).toList(),
                "producers", producers,
                "consumers", consumers,
                "topologicalOrder", topologicalSort() != null ? topologicalSort() : List.of(),
                "cycles", detectCycles().stream().map(List::copyOf).toList(),
                "functionCount", functions().size(),
                "edgeCount", edges().size()
        );
    }

    // ── Types ──

    public record Edge(String from, String to, String key) {}
}
