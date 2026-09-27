package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.entity.BatchSchema;
import com.runledger.entity.Run;
import com.runledger.repository.BatchSchemaRepository;
import com.runledger.repository.RunRepository;
import com.runledger.security.SecuredTransactionTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BatchSchemaService {

    private final RunRepository runRepository;
    private final BatchSchemaRepository batchSchemaRepository;
    private final ObjectMapper objectMapper;
    private final SecuredTransactionTemplate secured;

    public BatchSchemaService(RunRepository runRepository,
                              BatchSchemaRepository batchSchemaRepository,
                              ObjectMapper objectMapper,
                              SecuredTransactionTemplate secured) {
        this.runRepository = runRepository;
        this.batchSchemaRepository = batchSchemaRepository;
        this.objectMapper = objectMapper;
        this.secured = secured;
    }

    /**
     * Returns a key → list‑of‑paths mapping for the given batch.
     * The first entry in each list is the shallowest occurrence.
     *
     * <p>Wrapped in {@link SecuredTransactionTemplate}: this method reads from
     * {@code run} and writes to {@code batch_schema}, both of which are
     * scoped by RLS once Slice 6 is enabled.
     */
    public Map<String, List<String>> getOrCreateMapping(String batch) {
        return secured.execute(() -> {
            batchSchemaRepository.deleteById(batch);
            batchSchemaRepository.flush();
            return buildAndSaveMapping(batch);
        });
    }

    /**
     * Returns only the shorthand keys (what the user sees).
     *
     * <p>Not wrapped: this method performs no DB access of its own. It
     * delegates to {@code getOrCreateMapping}, which is wrapped. When called
     * from a wrapped context, the inner call joins the outer transaction;
     * when called directly, the inner call starts its own.
     */
    public Set<String> getAvailableKeys(String batch) {
        return getOrCreateMapping(batch).keySet();
    }

    /**
     * Force rebuild the mapping for a given batch and return it.
     *
     * <p>Not wrapped for the same reason as {@code getAvailableKeys} — it is
     * a semantic alias for {@code getOrCreateMapping}, which is wrapped.
     */
    public Map<String, List<String>> rebuildMapping(String batch) {
        return getOrCreateMapping(batch);
    }

    // ---------- private helpers ----------

    private Map<String, List<String>> buildAndSaveMapping(String batch) {
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();

        Map<String, Map<String, Integer>> temp = new LinkedHashMap<>();

        for (Run run : runs) {
            try {
                JsonNode payload = objectMapper.readTree(run.getPayload());
                collectLeafPaths("", payload, 0, temp);
            } catch (Exception ignored) {}
        }

        Map<String, List<String>> mapping = new LinkedHashMap<>();
        for (var entry : temp.entrySet()) {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(entry.getValue().entrySet());
            sorted.sort(Map.Entry.comparingByValue());
            List<String> paths = new ArrayList<>();
            for (var e : sorted) {
                paths.add(e.getKey());
            }
            mapping.put(entry.getKey(), paths);
        }

        BatchSchema schema = new BatchSchema();
        schema.setBatch(batch);
        schema.setKeyMapping(mapping);
        batchSchemaRepository.save(schema);

        return Collections.unmodifiableMap(mapping);
    }

    private void collectLeafPaths(String prefix, JsonNode node, int depth,
                                  Map<String, Map<String, Integer>> result) {
        if (node == null || node.isNull()) return;

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if ("_source".equals(key)) return;
                JsonNode value = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (value.isObject()) {
                    collectLeafPaths(path, value, depth + 1, result);
                } else if (value.isArray() && isArrayOfObjects(value)) {
                    collectArrayLeafPaths(path, value, depth + 1, result);
                } else {
                    result.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .putIfAbsent(path, depth);
                }
            });
        }
        if (node.isArray() && isArrayOfObjects(node)) {
            collectArrayLeafPaths(prefix, node, depth, result);
        }
    }

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