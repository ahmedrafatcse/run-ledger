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

    public Map<String, String> getOrCreateMapping(String batch) {
        batchSchemaRepository.deleteById(batch);
        batchSchemaRepository.flush();
        return buildAndSaveMapping(batch);
    }

    public Set<String> getAvailableKeys(String batch) {
        return getOrCreateMapping(batch).keySet();
    }

    public Map<String, String> rebuildMapping(String batch) {
        return getOrCreateMapping(batch);
    }

    private Map<String, String> buildAndSaveMapping(String batch) {
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();
        Map<String, String> mapping = new LinkedHashMap<>();

        for (Run run : runs) {
            try {
                JsonNode payload = objectMapper.readTree(run.getPayload());
                collectLeafPaths("", payload, 0, mapping);
            } catch (Exception ignored) {}
        }

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
                if ("_source".equals(key)) {
                    return;   // skip internal source metadata
                }
                JsonNode value = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (value.isObject()) {
                    collectLeafPaths(path, value, depth + 1, result);
                } else if (value.isArray() && isArrayOfObjects(value)) {
                    collectArrayLeafPaths(path, value, depth + 1, result);
                } else {
                    result.merge(key, path, (existing, newPath) ->
                            existing.split("\\.").length <= newPath.split("\\.").length
                                    ? existing : newPath);
                }
            });
        }
        if (node.isArray() && isArrayOfObjects(node)) {
            collectArrayLeafPaths(prefix, node, depth, result);
        }
    }

    private void collectArrayLeafPaths(String prefix, JsonNode array, int depth,
                                       Map<String, String> result) {
        for (JsonNode element : array) {
            if (element != null && element.isObject()) {
                element.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    if ("_source".equals(key)) {
                        return;   // skip source inside arrays (unlikely, but safe)
                    }
                    JsonNode value = entry.getValue();
                    String arrayPath = prefix + "[]." + key;

                    if (value.isObject()) {
                        collectLeafPaths(arrayPath, value, depth + 1, result);
                    } else {
                        result.putIfAbsent(key, arrayPath);
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