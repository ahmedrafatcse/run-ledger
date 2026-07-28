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
     * Returns a key → path mapping for the given batch.
     * <p>
     * Always rebuilds the mapping from the current runs in the batch,
     * so re‑scans pick up new keys immediately.
     *
     * @param batch the batch name
     * @return unmodifiable map of shorthand key → full dot‑separated path
     */
    public Map<String, String> getOrCreateMapping(String batch) {
        // Remove any stale mapping
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
     * (Redundant now because {@link #getOrCreateMapping} rebuilds every time,
     * but kept for backward compatibility.)
     */
    public Map<String, String> rebuildMapping(String batch) {
        return getOrCreateMapping(batch);
    }

    // ---------- private helpers ----------

    private Map<String, String> buildAndSaveMapping(String batch) {
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();
        Map<String, String> mapping = new LinkedHashMap<>();

        for (Run run : runs) {
            try {
                JsonNode payload = objectMapper.readTree(run.getPayload());
                collectLeafPaths("", payload, 0, mapping);
            } catch (Exception ignored) {
                // skip malformed JSON payloads
            }
        }

        // Save the mapping
        BatchSchema schema = new BatchSchema();
        schema.setBatch(batch);
        schema.setKeyMapping(toJsonString(mapping));
        batchSchemaRepository.save(schema);

        return Collections.unmodifiableMap(mapping);
    }

    /**
     * Recursively walks the JSON tree and records every leaf key.
     *
     * <p>For scalar leaves, the key is recorded with its dot‑path.
     * For arrays of objects, the key is recorded with a {@code []} suffix
     * (e.g. {@code results[].threshold}) and the shallowest path is kept.
     *
     * <p>If the same key exists both as a scalar and inside an array,
     * both entries are kept independently (different paths).
     *
     * @param prefix the dot‑separated path built so far (empty for root)
     * @param node   the current JSON node
     * @param depth  how many levels deep from the root (unused for now, but available)
     * @param result the accumulator: shorthand key → full dot‑path
     */
    private void collectLeafPaths(String prefix, JsonNode node, int depth,
                                  Map<String, String> result) {
        if (node == null || node.isNull()) return;

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (value.isObject()) {
                    collectLeafPaths(path, value, depth + 1, result);
                } else if (value.isArray() && isArrayOfObjects(value)) {
                    // Recurse into each array element to discover all leaf keys
                    collectArrayLeafPaths(path, value, depth + 1, result);
                } else {
                    // Scalar leaf – keep only the shallowest occurrence
                    result.merge(key, path, (existing, newPath) ->
                            existing.split("\\.").length <= newPath.split("\\.").length
                                    ? existing : newPath);
                }
            });
        }
        // arrays at the root level (edge case) – not expected, but handled
        if (node.isArray() && isArrayOfObjects(node)) {
            collectArrayLeafPaths(prefix, node, depth, result);
        }
    }

    /**
     * Iterates over every element of an array of objects and collects
     * leaf keys with bracket notation.
     *
     * <p>Each discovered key is stored as {@code prefix[].key}, e.g.
     * {@code client.results[].threshold}.  If the same key already exists
     * as a scalar path, both entries are kept independently.
     */
    private void collectArrayLeafPaths(String prefix, JsonNode array, int depth,
                                       Map<String, String> result) {
        for (JsonNode element : array) {
            if (element != null && element.isObject()) {
                element.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode value = entry.getValue();
                    String arrayPath = prefix + "[]." + key;

                    if (value.isObject()) {
                        // Recurse deeper if the element itself is an object
                        collectLeafPaths(arrayPath, value, depth + 1, result);
                    } else {
                        // Store with the bracket notation as the shorthand key
                        // We use the full bracket path as the stored value
                        result.putIfAbsent(key, arrayPath);
                    }
                });
            }
        }
    }

    /**
     * Returns true if the array contains at least one object element.
     * Arrays of primitives (strings, numbers) are not recursed into.
     */
    private boolean isArrayOfObjects(JsonNode array) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode element : array) {
            if (element != null && element.isObject()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> parseMapping(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, String> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> map.put(field.getKey(), field.getValue().asText()));
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String toJsonString(Map<String, String> mapping) {
        try {
            return objectMapper.writeValueAsString(mapping);
        } catch (Exception e) {
            return "{}";
        }
    }
}