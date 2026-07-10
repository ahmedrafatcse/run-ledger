package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RunQueryService {

    private final RunRepository runRepository;

    public RunQueryService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    /**
     * Execute a block‑search query with pagination support.
     *
     * @param metric   metric key (e.g. "accuracy")
     * @param op       operator: gt, gte, lt, lte, eq
     * @param value    the value to compare (parsed as double or kept as String)
     * @param pageable pagination and sorting information
     * @return a page of matching runs
     */
    public Page<Run> queryByMetric(String metric, String op, String value, Pageable pageable) {
        return switch (op) {
            case "gt"  -> runRepository.findByMetricGreaterThan(metric, parseDouble(value), pageable);
            case "gte" -> runRepository.findByMetricGreaterThanOrEqual(metric, parseDouble(value), pageable);
            case "lt"  -> runRepository.findByMetricLessThan(metric, parseDouble(value), pageable);
            case "lte" -> runRepository.findByMetricLessThanOrEqual(metric, parseDouble(value), pageable);
            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield runRepository.findByMetricEquals(metric, numericValue, pageable);
                } else {
                    yield runRepository.findByMetricEqualsText(metric, value, pageable);
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