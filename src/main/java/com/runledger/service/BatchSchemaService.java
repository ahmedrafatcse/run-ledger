package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.entity.BatchSchema;
import com.runledger.entity.Run;
import com.runledger.repository.BatchSchemaRepository;
import com.runledger.repository.RunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Manages the per-batch shorthand-key mapping.
 *
 * <p>As of Slice 4.5, the mapping is keyed by {@code (teamId, batch)}. The
 * same batch name in two teams is two distinct mappings. The team is
 * derived from {@link com.runledger.security.AppSecurityContext} at the
 * call site and passed in explicitly; the service never guesses it.
 *
 * <p>Reads are read-first: if a mapping row exists it is returned, otherwise
 * the mapping is computed from the runs the identity can see and returned
 * WITHOUT persisting. Only the ingest path writes. This means a supervisor
 * who has never ingested into a batch still gets a correct mapping,
 * without needing write access to {@code batch_schema}.
 *
 * <p>Writes merge. A new run's leaf paths are added to the existing mapping
 * under a transaction-scoped advisory lock keyed on {@code (teamId, batch)}.
 * Two concurrent ingests into the same batch serialize on this lock and
 * both runs' paths end up in the mapping.
 *
 * <p>Paths only accumulate. A path that existed in v1 and was removed in v2
 * stays in the mapping. This is intentional: shorthand resolution is a
 * discovery aid, and a path that ever existed in the batch is still worth
 * resolving for a query. Noted in DESIGN_DECISIONS.md.
 */
@Service
public class BatchSchemaService {

    private final RunRepository runRepository;
    private final BatchSchemaRepository batchSchemaRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public BatchSchemaService(RunRepository runRepository,
                              BatchSchemaRepository batchSchemaRepository,
                              ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.batchSchemaRepository = batchSchemaRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Read-first mapping lookup, scoped to a team.
     *
     * <p>If a stored mapping exists for (teamId, batch), returns it.
     * Otherwise computes a mapping from the runs visible to the caller
     * (RLS-filtered) without persisting.
     */
    public Map<String, List<String>> getOrCreateMapping(UUID teamId, String batch) {
        if (teamId == null) {
            throw new IllegalStateException(
                    "Batch mapping lookup requires a team. "
                            + "Supervisors must select a team explicitly.");
        }

        Optional<BatchSchema> existing =
                batchSchemaRepository.findByTeamIdAndBatch(teamId, batch);
        if (existing.isPresent()) {
            return existing.get().getKeyMapping();
        }

        // Miss: build from visible runs, do not persist.
        List<Run> runs = runRepository.findByBatch(batch, Pageable.unpaged()).getContent();
        return buildMappingFromRuns(runs);
    }

    /**
     * Merge a newly-ingested run's leaf paths into the (teamId, batch)
     * mapping. Called from {@link RunIngestionService#ingest} after the
     * run row is inserted.
     *
     * <p>Runs under a transaction-scoped advisory lock so concurrent
     * ingests into the same batch serialize on this merge. The lock key
     * is a hash of {@code teamId + ':' + batch}.
     *
     * <p>Assumes it is being called inside an already-open transaction.
     * The advisory lock is xact-scoped, so it releases when the caller's
     * transaction ends — the same discipline {@code SET LOCAL} uses.
     */
    @Transactional
    public void mergeIntoMapping(UUID teamId, String batch, JsonNode payload) {
        if (teamId == null || batch == null) {
            return;   // Nothing to merge into without both.
        }

        // Serialize concurrent merges for the same (team, batch).
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", teamId + ":" + batch)
                .getSingleResult();

        Optional<BatchSchema> existing =
                batchSchemaRepository.findByTeamIdAndBatch(teamId, batch);

        // Load existing mapping into a mutable depth-keyed structure.
        Map<String, Map<String, Integer>> temp = new LinkedHashMap<>();
        if (existing.isPresent()) {
            for (var entry : existing.get().getKeyMapping().entrySet()) {
                Map<String, Integer> paths = new LinkedHashMap<>();
                for (String path : entry.getValue()) {
                    paths.put(path, path.split("\\.").length);
                }
                temp.put(entry.getKey(), paths);
            }
        }

        // Merge the new run's paths.
        collectLeafPaths("", payload, 0, temp);

        // Convert to the final mapping shape: key -> list of paths, shallowest first.
        Map<String, List<String>> merged = toSortedMapping(temp);

        BatchSchema toSave = existing.orElseGet(BatchSchema::new);
        toSave.setTeamId(teamId);
        toSave.setBatch(batch);
        toSave.setKeyMapping(merged);
        batchSchemaRepository.save(toSave);
    }

    /** Convenience for callers that only need the keys. */
    public Set<String> getAvailableKeys(UUID teamId, String batch) {
        return getOrCreateMapping(teamId, batch).keySet();
    }

    // ── Private helpers ──

    private Map<String, List<String>> buildMappingFromRuns(List<Run> runs) {
        Map<String, Map<String, Integer>> temp = new LinkedHashMap<>();
        for (Run run : runs) {
            try {
                JsonNode payload = objectMapper.readTree(run.getPayload());
                collectLeafPaths("", payload, 0, temp);
            } catch (Exception ignored) {}
        }
        return toSortedMapping(temp);
    }

    private Map<String, List<String>> toSortedMapping(Map<String, Map<String, Integer>> temp) {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        for (var entry : temp.entrySet()) {
            List<Map.Entry<String, Integer>> sorted =
                    new ArrayList<>(entry.getValue().entrySet());
            sorted.sort(Map.Entry.comparingByValue());
            List<String> paths = new ArrayList<>();
            for (var e : sorted) {
                paths.add(e.getKey());
            }
            mapping.put(entry.getKey(), paths);
        }
        return mapping;
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
            if (element != null && element.isObject()) return true;
        }
        return false;
    }
}