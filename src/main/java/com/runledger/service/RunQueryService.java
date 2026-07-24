package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

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
        return executeQuery(metric, op, value, null, metric, pageable);
    }

    // ---------------------------------------------------------------
    // Paginated query – with batch filter
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value,
                                   String batch, Pageable pageable) {
        String resolvedPath = resolvePath(metric, batch);
        return executeQuery(resolvedPath, op, value, batch, metric, pageable);
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

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private String resolvePath(String metric, String batch) {
        if (metric.contains(".")) {
            return metric;                                      // already a full path
        }
        if (batch != null && !batch.isBlank()) {
            return batchSchemaService.getOrCreateMapping(batch)
                    .getOrDefault(metric, metric);             // shorthand → path or raw key
        }
        return metric;                                          // no batch → treat as top‑level key
    }

    private Page<Run> executeQuery(String path, String op, String value,
                                   String batch, String displayMetric,
                                   Pageable pageable) {
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