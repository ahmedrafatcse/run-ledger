package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.entity.BatchSchema;
import com.runledger.entity.Run;
import com.runledger.repository.BatchSchemaRepository;
import com.runledger.repository.RunRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BatchSchemaService {

    private final RunRepository runRepository;
    private final BatchSchemaRepository batchSchemaRepository;
    private final ObjectMapper objectMapper;

    public BatchSchemaService(RunRepository runRepository,
                              BatchSchemaRepository batchSchemaRepository,
                              ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.batchSchemaRepository = batchSchemaRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a key → list‑of‑paths mapping for the given batch.
     * The first entry in each list is the shallowest occurrence.
     */
    public Map<String, List<String>> getOrCreateMapping(String batch) {
        batchSchemaRepository.deleteById(batch);
        batchSchemaRepository.flush();
        return buildAndSaveMapping(batch);
    }

    /**
     * Returns only the shorthand keys (what the user sees).
     */
    public Set<String> getAvailableKeys(String batch) {
        return getOrCreateMapping(batch).keySet();
    }

    /**
     * Force rebuild the mapping for a given batch and return it.
     */
    public Map<String, List<String>> rebuildMapping(String batch) {
        return getOrCreateMapping(batch);
    }

    // ---------- private helpers ----------

    private Map<String, List<String>> buildAndSaveMapping(String batch) {
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();

        // Temporary collector: shorthand key → (path → depth)
        Map<String, Map<String, Integer>> temp = new LinkedHashMap<>();

        for (Run run : runs) {
            try {
                JsonNode payload = objectMapper.readTree(run.getPayload());
                collectLeafPaths("", payload, 0, temp);
            } catch (Exception ignored) {}
        }

        // Build final mapping: shorthand key → list of paths sorted by depth
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        for (var entry : temp.entrySet()) {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(entry.getValue().entrySet());
            sorted.sort(Map.Entry.comparingByValue());   // shallowest first
            List<String> paths = new ArrayList<>();
            for (var e : sorted) {
                paths.add(e.getKey());
            }
            mapping.put(entry.getKey(), paths);
        }

        // Persist
        BatchSchema schema = new BatchSchema();
        schema.setBatch(batch);
        schema.setKeyMapping(mapping);
        batchSchemaRepository.save(schema);

        return Collections.unmodifiableMap(mapping);
    }

    /**
     * Recursively walks the JSON tree and records every leaf key.
     * For scalar leaves, the key is recorded with its dot‑path.
     * For arrays of objects, the key is recorded with a {@code []} suffix
     * (e.g. {@code results[].threshold}).  Multiple occurrences of the same
     * shorthand key are all kept.
     */
    private void collectLeafPaths(String prefix, JsonNode node, int depth,
                                  Map<String, Map<String, Integer>> result) {
        if (node == null || node.isNull()) return;

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if ("_source".equals(key)) return;   // skip internal metadata
                JsonNode value = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (value.isObject()) {
                    collectLeafPaths(path, value, depth + 1, result);
                } else if (value.isArray() && isArrayOfObjects(value)) {
                    collectArrayLeafPaths(path, value, depth + 1, result);
                } else {
                    // scalar leaf
                    result.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .putIfAbsent(path, depth);
                }
            });
        }
        if (node.isArray() && isArrayOfObjects(node)) {
            collectArrayLeafPaths(prefix, node, depth, result);
        }
    }

    /**
     * Iterates over every element of an array of objects and collects
     * leaf keys with bracket notation.
     */
    private void collectArrayLeafPaths(String prefix, JsonNode array, int depth,
                                       Map<String, Map<String, Integer>> result) {
        for (JsonNode element : array) {
            if (element != null && element.isObject()) {
                element.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    if ("_source".equals(key)) return;
                    JsonNode value = entry.getValue();
                    String arrayPath = prefix + "[]." + key;

                    if (value.isObject()) {
                        collectLeafPaths(arrayPath, value, depth + 1, result);
                    } else {
                        result.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .putIfAbsent(arrayPath, depth);
                    }
                });
            }
        }
    }

    private boolean isArrayOfObjects(JsonNode array) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode element : array) {
            if (element != null && element.isObject()) {
                return true;
            }
        }
        return false;
    }

    // --- JSON serialization helpers (List<String> version) ---

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseMapping(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, List<String>> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                List<String> paths = new ArrayList<>();
                field.getValue().forEach(v -> paths.add(v.asText()));
                map.put(field.getKey(), Collections.unmodifiableList(paths));
            });
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String toJsonString(Map<String, List<String>> mapping) {
        try {
            return objectMapper.writeValueAsString(mapping);
        } catch (Exception e) {
            return "{}";
        }
    }
}