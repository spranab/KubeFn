package com.kubefn.runtime.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contract Compatibility Guard — blocks deploys that break heap consumers.
 *
 * <p>At runtime, tracks which heap keys and contract types each function accesses.
 * On hot-swap, compares the new contract types against consumer access profiles.
 * If a field that a consumer reads was removed or type-changed, blocks the deploy.
 *
 * <p>Uses runtime access data from HeapTrace (more accurate than static bytecode
 * analysis because it captures actual production access patterns).
 *
 * <p>Example:
 * <pre>
 *   PricingResult v1: {currency, basePrice, discount, finalPrice}
 *   PricingResult v2: {currency, basePrice, discountRate, finalPrice, margin}
 *
 *   TaxFunction reads: discount, finalPrice
 *
 *   Guard says: BLOCKED — TaxFunction reads 'discount' which was removed in v2.
 *   Suggestion: rename to 'discountRate'?
 * </pre>
 */
public class ContractCompatibilityGuard {
    private static final Logger log = LoggerFactory.getLogger(ContractCompatibilityGuard.class);

    /** function → {contractType → set of field names accessed} */
    private final ConcurrentHashMap<String, Map<String, Set<String>>> accessProfiles =
            new ConcurrentHashMap<>();

    /** contractType → set of field names in the current version */
    private final ConcurrentHashMap<String, ContractSchema> knownSchemas =
            new ConcurrentHashMap<>();

    /**
     * Record that a function accessed a contract type's fields.
     * Called from HeapExchange on get() when we can reflect on the type.
     */
    public void recordAccess(String functionName, String contractType, Set<String> fieldsAccessed) {
        accessProfiles
                .computeIfAbsent(functionName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(contractType, k -> ConcurrentHashMap.newKeySet())
                .addAll(fieldsAccessed);
    }

    /**
     * Register the schema of a contract type (from the loaded class).
     */
    public void registerSchema(Class<?> contractClass) {
        var schema = extractSchema(contractClass);
        knownSchemas.put(contractClass.getSimpleName(), schema);
        log.info("Registered contract schema: {} ({} fields)",
                contractClass.getSimpleName(), schema.fields().size());
    }

    /**
     * Check compatibility when a new version of a contract is deployed.
     *
     * @param contractType the contract type name (e.g. "PricingResult")
     * @param newClass     the new version of the class
     * @return verdict with details
     */
    public CompatibilityVerdict checkCompatibility(String contractType, Class<?> newClass) {
        ContractSchema oldSchema = knownSchemas.get(contractType);
        if (oldSchema == null) {
            return new CompatibilityVerdict(true, "No previous schema known. Allowing.",
                    List.of(), List.of());
        }

        ContractSchema newSchema = extractSchema(newClass);

        // Find removed fields
        var removedFields = new LinkedHashSet<>(oldSchema.fields().keySet());
        removedFields.removeAll(newSchema.fields().keySet());

        // Find type-changed fields
        var changedFields = new ArrayList<String>();
        for (var entry : oldSchema.fields().entrySet()) {
            String fieldName = entry.getKey();
            String oldType = entry.getValue();
            String newType = newSchema.fields().get(fieldName);
            if (newType != null && !oldType.equals(newType)) {
                changedFields.add(fieldName + " (" + oldType + " → " + newType + ")");
            }
        }

        // Find added fields (informational, not breaking)
        var addedFields = new LinkedHashSet<>(newSchema.fields().keySet());
        addedFields.removeAll(oldSchema.fields().keySet());

        // Check which consumers are affected
        var violations = new ArrayList<Violation>();

        for (var funcEntry : accessProfiles.entrySet()) {
            String functionName = funcEntry.getKey();
            Map<String, Set<String>> funcAccess = funcEntry.getValue();
            Set<String> accessedFields = funcAccess.getOrDefault(contractType, Set.of());

            for (String removed : removedFields) {
                if (accessedFields.contains(removed)) {
                    // Check for likely rename
                    String suggestion = findLikelyRename(removed, addedFields, newSchema);
                    violations.add(new Violation(
                            functionName, contractType, removed,
                            ViolationType.FIELD_REMOVED,
                            suggestion != null ? "Renamed to '" + suggestion + "'?" : null
                    ));
                }
            }

            for (String changed : changedFields) {
                String fieldName = changed.split(" ")[0];
                if (accessedFields.contains(fieldName)) {
                    violations.add(new Violation(
                            functionName, contractType, fieldName,
                            ViolationType.TYPE_CHANGED,
                            changed
                    ));
                }
            }
        }

        boolean compatible = violations.isEmpty();
        String summary;
        if (compatible) {
            summary = String.format("%s: compatible. %d fields added, %d removed (unused), %d changed (unused).",
                    contractType, addedFields.size(), removedFields.size(), changedFields.size());
        } else {
            summary = String.format("%s: INCOMPATIBLE. %d breaking violation(s) affecting %d consumer(s).",
                    contractType, violations.size(),
                    violations.stream().map(Violation::functionName).distinct().count());
        }

        log.info("ContractGuard: {}", summary);

        return new CompatibilityVerdict(compatible, summary, violations,
                addedFields.stream().toList());
    }

    /**
     * Extract schema (field names + types) from a class.
     * Supports records, POJOs with getters, and public fields.
     */
    private ContractSchema extractSchema(Class<?> clazz) {
        var fields = new LinkedHashMap<String, String>();

        // Try records first
        if (clazz.isRecord()) {
            for (RecordComponent rc : clazz.getRecordComponents()) {
                fields.put(rc.getName(), rc.getType().getSimpleName());
            }
            return new ContractSchema(clazz.getSimpleName(), fields);
        }

        // Try getters (getX/isX)
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (m.getParameterCount() == 0 && m.getDeclaringClass() != Object.class) {
                String fieldName = null;
                if (name.startsWith("get") && name.length() > 3) {
                    fieldName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                } else if (name.startsWith("is") && name.length() > 2
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    fieldName = Character.toLowerCase(name.charAt(2)) + name.substring(3);
                }
                if (fieldName != null) {
                    fields.put(fieldName, m.getReturnType().getSimpleName());
                }
            }
        }

        // Try public fields as fallback
        if (fields.isEmpty()) {
            for (var f : clazz.getFields()) {
                fields.put(f.getName(), f.getType().getSimpleName());
            }
        }

        return new ContractSchema(clazz.getSimpleName(), fields);
    }

    /**
     * Heuristic: find a likely rename among added fields.
     * Match by same type and similar name (edit distance <= 3).
     */
    private String findLikelyRename(String removedField, Set<String> addedFields,
                                     ContractSchema newSchema) {
        String removedLower = removedField.toLowerCase();
        for (String added : addedFields) {
            if (editDistance(removedLower, added.toLowerCase()) <= 3) {
                return added;
            }
        }
        return null;
    }

    private int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    public Map<String, Object> status() {
        var profiles = new LinkedHashMap<String, Object>();
        for (var entry : accessProfiles.entrySet()) {
            profiles.put(entry.getKey(), entry.getValue());
        }
        var schemas = new LinkedHashMap<String, Object>();
        for (var entry : knownSchemas.entrySet()) {
            schemas.put(entry.getKey(), entry.getValue().fields());
        }
        return Map.of(
                "accessProfiles", profiles,
                "knownSchemas", schemas,
                "trackedFunctions", accessProfiles.size(),
                "trackedContracts", knownSchemas.size()
        );
    }

    // ── Types ──

    public record ContractSchema(String typeName, Map<String, String> fields) {}

    public enum ViolationType {
        FIELD_REMOVED,
        TYPE_CHANGED
    }

    public record Violation(
            String functionName,
            String contractType,
            String fieldName,
            ViolationType type,
            String suggestion
    ) {
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("function", functionName);
            map.put("contract", contractType);
            map.put("field", fieldName);
            map.put("violation", type.name());
            if (suggestion != null) map.put("suggestion", suggestion);
            return map;
        }
    }

    public record CompatibilityVerdict(
            boolean compatible,
            String summary,
            List<Violation> violations,
            List<String> addedFields
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "compatible", compatible,
                    "summary", summary,
                    "violations", violations.stream().map(Violation::toMap).toList(),
                    "addedFields", addedFields
            );
        }
    }
}
