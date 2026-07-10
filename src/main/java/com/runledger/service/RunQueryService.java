package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunQueryService {

    private final RunRepository runRepository;

    public RunQueryService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    // ---------------------------------------------------------------
    // Paginated query – no batch filter (existing behaviour)
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value, Pageable pageable) {
        return queryByMetricInternal(metric, op, value, null, pageable);
    }

    // ---------------------------------------------------------------
    // Paginated query – with batch filter (new)
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value,
                                   String batch, Pageable pageable) {
        return queryByMetricInternal(metric, op, value, batch, pageable);
    }

    // ---------------------------------------------------------------
    // Internal dispatcher – routes to the correct repository method
    // ---------------------------------------------------------------
    private Page<Run> queryByMetricInternal(String metric, String op, String value,
                                            String batch, Pageable pageable) {
        boolean hasBatch = (batch != null && !batch.isBlank());

        return switch (op) {
            case "gt"  -> hasBatch
                    ? runRepository.findByMetricGreaterThanBatch(metric, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricGreaterThan(metric, parseDouble(value), pageable);

            case "gte" -> hasBatch
                    ? runRepository.findByMetricGreaterThanOrEqualBatch(metric, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricGreaterThanOrEqual(metric, parseDouble(value), pageable);

            case "lt"  -> hasBatch
                    ? runRepository.findByMetricLessThanBatch(metric, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricLessThan(metric, parseDouble(value), pageable);

            case "lte" -> hasBatch
                    ? runRepository.findByMetricLessThanOrEqualBatch(metric, parseDouble(value), batch, pageable)
                    : runRepository.findByMetricLessThanOrEqual(metric, parseDouble(value), pageable);

            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsBatch(metric, numericValue, batch, pageable)
                            : runRepository.findByMetricEquals(metric, numericValue, pageable);
                } else {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsTextBatch(metric, value, batch, pageable)
                            : runRepository.findByMetricEqualsText(metric, value, pageable);
                }
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported operator: " + op + ". Allowed: gt, gte, lt, lte, eq.");
        };
    }

    // ---------------------------------------------------------------
    // Metric key discovery – without / with batch
    // ---------------------------------------------------------------

    public List<String> getAvailableMetrics() {
        return runRepository.findDistinctMetricKeys();
    }

    public List<String> getAvailableMetrics(String batch) {
        return runRepository.findDistinctMetricKeysByBatch(batch);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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