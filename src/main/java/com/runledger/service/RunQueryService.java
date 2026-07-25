package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RunQueryService {

    private final RunRepository runRepository;
    private final BatchSchemaService batchSchemaService;

    public RunQueryService(RunRepository runRepository,
                           BatchSchemaService batchSchemaService) {
        this.runRepository = runRepository;
        this.batchSchemaService = batchSchemaService;
    }

    // ---------------------------------------------------------------
    // Paginated query – no batch filter
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value, Pageable pageable) {
        return executeQuery(metric, op, value, null, pageable);
    }

    // ---------------------------------------------------------------
    // Paginated query – with batch filter
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value,
                                   String batch, Pageable pageable) {
        String resolvedPath = resolvePathInternal(metric, batch);       // <-- now calls the private method
        return executeQuery(resolvedPath, op, value, batch, pageable);
    }

    // ---------------------------------------------------------------
    // Full‑text phrase search (no batch)
    // ---------------------------------------------------------------
    public Page<Run> searchByPhrase(String phrase, Pageable pageable) {
        return runRepository.searchByPhrase(phrase, pageable);
    }

    // ---------------------------------------------------------------
    // Full‑text phrase search (with batch)
    // ---------------------------------------------------------------
    public Page<Run> searchByPhrase(String phrase, String batch, Pageable pageable) {
        return runRepository.searchByPhraseBatch(phrase, batch, pageable);
    }

    // ---------------------------------------------------------------
    // Fuzzy trigram search (no batch)
    // ---------------------------------------------------------------
    public Page<Run> searchByFuzzy(String term, double threshold, Pageable pageable) {
        return runRepository.searchByFuzzy(term, threshold, pageable);
    }

    // ---------------------------------------------------------------
    // Fuzzy trigram search (with batch)
    // ---------------------------------------------------------------
    public Page<Run> searchByFuzzy(String term, double threshold, String batch, Pageable pageable) {
        return runRepository.searchByFuzzyBatch(term, threshold, batch, pageable);
    }

    // ---------------------------------------------------------------
    // Metric key discovery – without / with batch
    // ---------------------------------------------------------------
    public List<String> getAvailableMetrics() {
        return runRepository.findDistinctMetricKeys();
    }

    public List<String> getAvailableMetrics(String batch) {
        if (batch == null || batch.isBlank()) {
            return runRepository.findDistinctMetricKeys();
        }
        Collection<String> keys = batchSchemaService.getAvailableKeys(batch);
        return keys.stream().sorted().toList();
    }

    /**
     * Returns a list of maps, each containing key, path, and depth.
     * Used by the verbose metrics endpoint.
     */
    public List<Map<String, Object>> getAvailableMetricsVerbose(String batch) {
        Map<String, String> mapping = batchSchemaService.getOrCreateMapping(batch);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : mapping.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.getKey());
            item.put("path", entry.getValue());
            item.put("depth", entry.getValue().split("\\.").length);
            result.add(item);
        }
        return result;
    }

    /**
     * Public helper so the controller can obtain the resolved full path
     * (e.g. "metrics.accuracy") for a given shorthand and batch.
     */
    public String resolvePath(String metric, String batch) {
        return resolvePathInternal(metric, batch);
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private String resolvePathInternal(String metric, String batch) {
        if (metric.contains(".")) {
            return metric;
        }
        if (batch != null && !batch.isBlank()) {
            return batchSchemaService.getOrCreateMapping(batch)
                    .getOrDefault(metric, metric);
        }
        return metric;
    }

    private Page<Run> executeQuery(String path, String op, String value,
                                   String batch, Pageable pageable) {
        boolean hasBatch = (batch != null && !batch.isBlank());

        return switch (op) {
            case "gt"  -> hasBatch
                    ? runRepository.findByMetricGreaterThanBatch(path, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricGreaterThan(path, parseDouble(value), pageable);
            case "gte" -> hasBatch
                    ? runRepository.findByMetricGreaterThanOrEqualBatch(path, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricGreaterThanOrEqual(path, parseDouble(value), pageable);
            case "lt"  -> hasBatch
                    ? runRepository.findByMetricLessThanBatch(path, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricLessThan(path, parseDouble(value), pageable);
            case "lte" -> hasBatch
                    ? runRepository.findByMetricLessThanOrEqualBatch(path, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricLessThanOrEqual(path, parseDouble(value), pageable);
            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsBatch(path, numericValue, batch, pageable)
                            : runRepository.findByMetricEquals(path, numericValue, pageable);
                } else {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsTextBatch(path, value, batch, pageable)
                            : runRepository.findByMetricEqualsText(path, value, pageable);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported operator: " + op + ". Allowed: gt, gte, lt, lte, eq.");
        };
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a numeric value, got: " + value);
        }
    }

    private Double tryParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}