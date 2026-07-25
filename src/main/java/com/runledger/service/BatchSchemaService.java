package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.entity.BatchSchema;
import com.runledger.entity.Run;
import com.runledger.repository.BatchSchemaRepository;
import com.runledger.repository.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;

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
     * If the mapping doesn't exist yet, it is built from all runs in the batch.
     *
     * @param batch the batch name
     * @return unmodifiable map of shorthand key → full dot‑separated path
     */
    public Map<String, String> getOrCreateMapping(String batch) {
        // 1. Try the database first
        return batchSchemaRepository.findByBatch(batch)
                .map(schema -> parseMapping(schema.getKeyMapping()))
                .orElseGet(() -> buildAndSaveMapping(batch));
    }

    /**
     * Returns only the shorthand keys (what the user sees).
     */
    public Set<String> getAvailableKeys(String batch) {
        return getOrCreateMapping(batch).keySet();
    }

    // ---------- private helpers ----------

    private Map<String, String> buildAndSaveMapping(String batch) {
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();
        Map<String, String> mapping = new HashMap<>();

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
                } else {
                    // Leaf – keep only the shallowest occurrence
                    result.merge(key, path, (existing, newPath) ->
                            existing.split("\\.").length <= newPath.split("\\.").length ? existing : newPath);
                }
            });
        }
        // arrays, strings, numbers, booleans → treated as leaf (already handled by else branch)
    }

    private Map<String, String> parseMapping(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, String> map = new HashMap<>();
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