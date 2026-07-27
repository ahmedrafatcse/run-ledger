package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.Filter;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RunQueryService {

    private final RunRepository runRepository;
    private final BatchSchemaService batchSchemaService;
    private final CompoundQueryBuilder compoundQueryBuilder;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public RunQueryService(RunRepository runRepository,
                           BatchSchemaService batchSchemaService,
                           CompoundQueryBuilder compoundQueryBuilder,
                           EntityManager entityManager,
                           ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.batchSchemaService = batchSchemaService;
        this.compoundQueryBuilder = compoundQueryBuilder;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
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
        String resolvedPath = resolvePath(metric, batch);
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

    public String resolvePath(String metric, String batch) {
        return resolvePathInternal(metric, batch);
    }

    // ---------------------------------------------------------------
    // Multi‑condition AND/OR query
    // ---------------------------------------------------------------
    public Page<Run> queryByMultipleFilters(MultiFilterRequest request, Pageable pageable) {
        List<ResolvedFilter> resolved = new ArrayList<>();
        for (Filter f : request.filters()) {
            String path = resolvePath(f.metric(), request.batch());
            resolved.add(new ResolvedFilter(path, f.op(), f.value()));
        }

        CompoundQueryBuilder.CompoundQuery q = compoundQueryBuilder.build(resolved, request.combine());

        String dataSql = "SELECT r.* FROM run r WHERE " + q.whereClause() + " ORDER BY r.created_at DESC";
        String countSql = "SELECT count(*) FROM run r WHERE " + q.countWhere();

        if (request.batch() != null && !request.batch().isBlank()) {
            String batchParam = "batch_" + UUID.randomUUID().toString().replace("-", "");
            dataSql = "SELECT r.* FROM run r WHERE r.batch = :" + batchParam + " AND " + q.whereClause() + " ORDER BY r.created_at DESC";
            countSql = "SELECT count(*) FROM run r WHERE r.batch = :" + batchParam + " AND " + q.countWhere();
            q.params().put(batchParam, request.batch());
        }

        return runRepository.findByCompoundFilter(dataSql, q.params(), countSql, pageable);
    }

    // ---------------------------------------------------------------
    // Pointer / match localisation methods
    // ---------------------------------------------------------------

    public List<MatchDetail> getScalarMatch(Run run, String resolvedPath, String op, String value) {
        try {
            JsonNode root = objectMapper.readTree(run.getPayload());
            JsonNode leafNode = root.at(jsonPointer(resolvedPath));
            if (leafNode.isMissingNode()) return List.of();

            if (!matchesCondition(leafNode, op, value)) return List.of();

            JsonNode snippet = getParent(root, resolvedPath);
            Object matchedValue = leafNode.isNumber() ? leafNode.numberValue() : leafNode.textValue();
            String pointer = toHumanPointer(resolvedPath);
            return new ArrayList<>(List.of(new MatchDetail(pointer, snippet, matchedValue)));
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<Long, List<MatchDetail>> getArrayMatches(List<Long> runIds, String arrayPath,
                                                        String leafParts, String op, String value) {
        if (runIds.isEmpty()) return Map.of();

        String idsCsv = runIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        String predicate = buildArrayPredicate(op, value);
        String sql = """
            SELECT r.id,
                   (arr.idx - 1) AS array_index,
                   arr.elem            AS snippet,
                   arr.elem #> string_to_array(:leafParts, ',') AS value
            FROM run r
            CROSS JOIN LATERAL jsonb_array_elements(r.payload #> string_to_array(:arrayPath, '.'))
                                WITH ORDINALITY AS arr(elem, idx)
            WHERE r.id = ANY(string_to_array(:ids, ',')::bigint[])
              AND jsonb_typeof(arr.elem #> string_to_array(:leafParts, ',')) = :jsonType
              AND (""" + predicate + ")";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("ids", idsCsv);
        query.setParameter("arrayPath", arrayPath);
        query.setParameter("leafParts", leafParts);
        query.setParameter("jsonType", isNumeric(value) ? "number" : "string");
        setPredicateParams(query, op, value);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<Long, List<MatchDetail>> result = new HashMap<>();
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            int idx = ((Number) row[1]).intValue();
            String snippetJson = (String) row[2];
            JsonNode snippet = safeReadTree(snippetJson);
            Object val = convertValue(row[3]);
            String pointer = arrayPath + "[" + idx + "]." + leafParts.replace(",", ".");
            result.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new MatchDetail(pointer, snippet, val));
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private String resolvePathInternal(String metric, String batch) {
        if (metric.contains(".") || metric.contains("[]")) {
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

        if (path.contains("[]")) {
            return executeArrayQuery(path, op, value, batch, pageable);
        }

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

    private Page<Run> executeArrayQuery(String path, String op, String value,
                                        String batch, Pageable pageable) {
        boolean hasBatch = (batch != null && !batch.isBlank());
        String jsonbPath = path.replace("[]", "[*]");
        int lastDot = jsonbPath.lastIndexOf('.');
        String leaf = jsonbPath.substring(lastDot + 1);
        String pathForJsonb = jsonbPath.substring(0, lastDot);

        return switch (op) {
            case "gt"  -> hasBatch
                    ? runRepository.findByArrayGreaterThanBatch(pathForJsonb, leaf, parseDouble(value), batch, pageable)
                    : runRepository.findByArrayGreaterThan(pathForJsonb, leaf, parseDouble(value), pageable);
            case "gte" -> hasBatch
                    ? runRepository.findByArrayGreaterThanOrEqualBatch(pathForJsonb, leaf, parseDouble(value), batch, pageable)
                    : runRepository.findByArrayGreaterThanOrEqual(pathForJsonb, leaf, parseDouble(value), pageable);
            case "lt"  -> hasBatch
                    ? runRepository.findByArrayLessThanBatch(pathForJsonb, leaf, parseDouble(value), batch, pageable)
                    : runRepository.findByArrayLessThan(pathForJsonb, leaf, parseDouble(value), pageable);
            case "lte" -> hasBatch
                    ? runRepository.findByArrayLessThanOrEqualBatch(pathForJsonb, leaf, parseDouble(value), batch, pageable)
                    : runRepository.findByArrayLessThanOrEqual(pathForJsonb, leaf, parseDouble(value), pageable);
            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield hasBatch
                            ? runRepository.findByArrayEqualsBatch(pathForJsonb, leaf, numericValue, batch, pageable)
                            : runRepository.findByArrayEquals(pathForJsonb, leaf, numericValue, pageable);
                } else {
                    yield hasBatch
                            ? runRepository.findByArrayEqualsTextBatch(pathForJsonb, leaf, value, batch, pageable)
                            : runRepository.findByArrayEqualsText(pathForJsonb, leaf, value, pageable);
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

    // --- Pointer helpers ---

    private String buildArrayPredicate(String op, String value) {
        boolean numeric = isNumeric(value);
        return switch (op) {
            case "gt", "gte", "lt", "lte" -> {
                if (!numeric) throw new IllegalArgumentException("Operator " + op + " requires a numeric value");
                yield "(arr.elem #>> string_to_array(:leafParts, ','))::numeric " +
                        opSymbol(op) + " :val";
            }
            case "eq" -> numeric
                    ? "(arr.elem #>> string_to_array(:leafParts, ','))::numeric = :val"
                    : "arr.elem #>> string_to_array(:leafParts, ',') = :val";
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    private String opSymbol(String op) {
        return switch (op) {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            case "eq" -> "=";
            default -> throw new IllegalArgumentException("Unknown op: " + op);
        };
    }

    private void setPredicateParams(Query query, String op, String value) {
        if (isNumeric(value)) {
            query.setParameter("val", Double.parseDouble(value));
        } else {
            query.setParameter("val", value);
        }
    }

    private boolean isNumeric(String value) {
        if (value == null) return false;
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean matchesCondition(JsonNode leafNode, String op, String value) {
        if (leafNode.isNumber()) {
            double leafVal = leafNode.asDouble();
            double compVal = Double.parseDouble(value);
            return switch (op) {
                case "gt" -> leafVal > compVal;
                case "gte" -> leafVal >= compVal;
                case "lt" -> leafVal < compVal;
                case "lte" -> leafVal <= compVal;
                case "eq" -> leafVal == compVal;
                default -> false;
            };
        } else {
            String leafText = leafNode.asText();
            return "eq".equals(op) && leafText.equals(value);
        }
    }

    private String jsonPointer(String dotPath) {
        return "/" + dotPath.replace(".", "/");
    }

    private String toHumanPointer(String dotPath) {
        return dotPath;
    }

    private JsonNode getParent(JsonNode root, String dotPath) {
        int lastDot = dotPath.lastIndexOf('.');
        if (lastDot == -1) return root;
        String parentPath = dotPath.substring(0, lastDot);
        return root.at(jsonPointer(parentPath));
    }

    private JsonNode safeReadTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Object convertValue(Object dbValue) {
        if (dbValue instanceof Number) return ((Number) dbValue).doubleValue();
        return dbValue.toString();
    }
}