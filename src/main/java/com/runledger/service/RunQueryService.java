package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.Filter;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.SecuredTransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final SecuredTransactionTemplate secured;

    public RunQueryService(RunRepository runRepository,
                           BatchSchemaService batchSchemaService,
                           CompoundQueryBuilder compoundQueryBuilder,
                           EntityManager entityManager,
                           ObjectMapper objectMapper,
                           SecuredTransactionTemplate secured) {
        this.runRepository = runRepository;
        this.batchSchemaService = batchSchemaService;
        this.compoundQueryBuilder = compoundQueryBuilder;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.secured = secured;
    }

    // ---------------------------------------------------------------
    // Paginated query – no batch filter
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value, Pageable pageable) {
        return secured.execute(() -> executeQuery(metric, op, value, null, pageable));
    }

    // ---------------------------------------------------------------
    // Paginated query – with batch filter
    // ---------------------------------------------------------------
    public Page<Run> queryByMetric(String metric, String op, String value,
                                   String batch, Pageable pageable) {
        return secured.execute(() -> {
            String resolvedPath = resolvePath(metric, batch);
            return executeQuery(resolvedPath, op, value, batch, pageable);
        });
    }

    // ---------------------------------------------------------------
    // Full-text phrase search
    // ---------------------------------------------------------------
    public Page<Run> searchByPhrase(String phrase, Pageable pageable) {
        return secured.execute(() -> runRepository.searchByPhrase(phrase, pageable));
    }

    public Page<Run> searchByPhrase(String phrase, String batch, Pageable pageable) {
        return secured.execute(() -> runRepository.searchByPhraseBatch(phrase, batch, pageable));
    }

    // ---------------------------------------------------------------
    // Fuzzy trigram search
    // ---------------------------------------------------------------
    public Page<Run> searchByFuzzy(String term, double threshold, Pageable pageable) {
        return secured.execute(() -> runRepository.searchByFuzzy(term, threshold, pageable));
    }

    public Page<Run> searchByFuzzy(String term, double threshold, String batch, Pageable pageable) {
        return secured.execute(() -> runRepository.searchByFuzzyBatch(term, threshold, batch, pageable));
    }

    // ---------------------------------------------------------------
    // Metric key discovery
    // ---------------------------------------------------------------
    public List<String> getAvailableMetrics() {
        return secured.execute(() -> runRepository.findDistinctMetricKeys());
    }

    public List<String> getAvailableMetrics(String batch) {
        return secured.execute(() -> {
            if (batch == null || batch.isBlank()) {
                return runRepository.findDistinctMetricKeys();
            }
            Map<String, List<String>> mapping =
                    batchSchemaService.getOrCreateMapping(currentTeamIdForMapping(), batch);
            return mapping.keySet().stream().sorted().toList();
        });
    }

    public List<Map<String, Object>> getAvailableMetricsVerbose(String batch) {
        return secured.execute(() -> {
            Map<String, List<String>> mapping =
                    batchSchemaService.getOrCreateMapping(currentTeamIdForMapping(), batch);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var entry : mapping.entrySet()) {
                for (String path : entry.getValue()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", entry.getKey());
                    item.put("path", path);
                    item.put("depth", path.split("\\.").length);
                    result.add(item);
                }
            }
            return result;
        });
    }

    public String resolvePath(String metric, String batch) {
        return resolvePathInternal(metric, batch);
    }

    // ---------------------------------------------------------------
    // Slice 5 follow-up: wrapped read methods
    // ---------------------------------------------------------------
    public Optional<Run> getRunById(Long id) {
        return secured.execute(() -> runRepository.findById(id));
    }

    public Page<Run> listRuns(String batch, Pageable pageable) {
        return secured.execute(() -> {
            if (batch != null && !batch.isBlank()) {
                return runRepository.findByBatch(batch, pageable);
            }
            return runRepository.findAll(pageable);
        });
    }

    public List<Run> getVersions(String batch, String sourceFile, int sourceIndex) {
        return secured.execute(() ->
                runRepository.findByBatchAndSourceFileAndSourceIndexOrderByVersionAsc(
                        batch, sourceFile, sourceIndex));
    }

    // ---------------------------------------------------------------
    // Multi-condition AND/OR query
    // ---------------------------------------------------------------
    public Page<Run> queryByMultipleFilters(MultiFilterRequest request, Pageable pageable) {
        return secured.execute(() -> {
            List<ResolvedFilter> resolved = new ArrayList<>();
            for (Filter f : request.filters()) {
                String path = resolvePath(f.metric(), request.batch());
                resolved.add(new ResolvedFilter(path, f.op(), f.value()));
            }

            CompoundQueryBuilder.CompoundQuery q = compoundQueryBuilder.build(resolved, request.combine());

            String dataSql = "SELECT r.* FROM run r WHERE r.latest = true AND " + q.whereClause() + " ORDER BY r.created_at DESC";
            String countSql = "SELECT count(*) FROM run r WHERE r.latest = true AND " + q.countWhere();

            if (request.batch() != null && !request.batch().isBlank()) {
                String batchParam = "batch_" + UUID.randomUUID().toString().replace("-", "");
                dataSql = "SELECT r.* FROM run r WHERE r.latest = true AND r.batch = :" + batchParam + " AND " + q.whereClause() + " ORDER BY r.created_at DESC";
                countSql = "SELECT count(*) FROM run r WHERE r.latest = true AND r.batch = :" + batchParam + " AND " + q.countWhere();
                q.params().put(batchParam, request.batch());
            }

            return runRepository.findByCompoundFilter(dataSql, q.params(), countSql, stripSort(pageable));
        });
    }

    // ---------------------------------------------------------------
    // Pointer / match localisation
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

    /**
     * Extract match pointers for a page of runs against a resolved path.
     *
     * <p>Chooses between array-element extraction (path contains {@code []})
     * and scalar extraction (path is a plain dot-path). Returns a map from
     * run ID to the list of matches for that run. Runs with no match are
     * absent from the map.
     */
    public Map<Long, List<MatchDetail>> findMatches(List<Run> runs,
                                                    String resolvedPath,
                                                    String op,
                                                    String value) {
        if (runs == null || runs.isEmpty() || resolvedPath == null) {
            return Map.of();
        }

        if (resolvedPath.contains("[]")) {
            List<Long> ids = runs.stream().map(Run::getId).toList();
            return getArrayMatches(ids, resolvedPath, op, value);
        }

        Map<Long, List<MatchDetail>> result = new HashMap<>();
        for (Run run : runs) {
            List<MatchDetail> matches = getScalarMatch(run, resolvedPath, op, value);
            if (!matches.isEmpty()) {
                result.put(run.getId(), matches);
            }
        }
        return result;
    }

    public Map<Long, List<MatchDetail>> getArrayMatches(List<Long> runIds, String fullPath,
                                                        String op, String value) {
        return secured.execute(() -> {
            if (runIds.isEmpty()) return Map.of();

            String idsCsv = runIds.stream().map(String::valueOf).collect(Collectors.joining(","));

            List<String> segments = new ArrayList<>();
            int start = 0;
            while (start < fullPath.length()) {
                int bracket = fullPath.indexOf("[]", start);
                int dot = fullPath.indexOf('.', start);
                if (bracket == -1 && dot == -1) {
                    segments.add(fullPath.substring(start));
                    break;
                }
                if (bracket != -1 && (dot == -1 || bracket < dot)) {
                    if (bracket > start) segments.add(fullPath.substring(start, bracket));
                    segments.add("[]");
                    start = bracket + 2;
                    if (start < fullPath.length() && fullPath.charAt(start) == '.') start++;
                } else {
                    if (dot > start) segments.add(fullPath.substring(start, dot));
                    start = dot + 1;
                }
            }

            StringBuilder from = new StringBuilder("FROM run r");
            List<String> arrayPaths = new ArrayList<>();
            List<String> indexAliases = new ArrayList<>();
            int arrayCount = 0;
            List<String> pathSegments = new ArrayList<>();

            for (String seg : segments) {
                if (seg.equals("[]")) {
                    String current = pathSegments.isEmpty() ? "" : String.join(".", pathSegments);
                    arrayPaths.add(current);
                    String alias = "arr" + arrayCount;
                    String idxAlias = "idx" + arrayCount;
                    String source = (arrayCount == 0) ? "r.payload" : ("arr" + (arrayCount - 1) + ".elem");
                    from.append(" CROSS JOIN LATERAL jsonb_array_elements(")
                            .append(source).append(" #> string_to_array(:arrayPath")
                            .append(arrayCount).append(", '.')) WITH ORDINALITY AS ").append(alias)
                            .append("(elem, ").append(idxAlias).append(")");
                    indexAliases.add(idxAlias);
                    arrayCount++;
                    pathSegments.clear();
                } else {
                    pathSegments.add(seg);
                }
            }

            String leafParts = String.join(",", pathSegments);

            String innermostAlias = "arr" + (arrayCount - 1);
            String predicate = buildArrayPredicate(op, value, innermostAlias);

            StringBuilder sql = new StringBuilder("SELECT r.id, ");
            for (int i = 0; i < arrayCount; i++) {
                sql.append("(").append(indexAliases.get(i)).append(" - 1) AS idx").append(i).append(", ");
            }
            sql.append(innermostAlias).append(".elem AS snippet, ")
                    .append(innermostAlias).append(".elem #> string_to_array(:leafParts, ',') AS value ")
                    .append(from)
                    .append(" WHERE r.id = ANY(string_to_array(:ids, ',')::bigint[])")
                    .append(" AND r.latest = true")
                    .append(" AND jsonb_typeof(").append(innermostAlias)
                    .append(".elem #> string_to_array(:leafParts, ',')) = :jsonType")
                    .append(" AND (")
                    .append(predicate)
                    .append(")");

            Query query = entityManager.createNativeQuery(sql.toString());
            query.setParameter("ids", idsCsv);
            for (int i = 0; i < arrayCount; i++) {
                query.setParameter("arrayPath" + i, arrayPaths.get(i));
            }
            query.setParameter("leafParts", leafParts);
            query.setParameter("jsonType", isNumeric(value) ? "number" : "string");
            setPredicateParams(query, op, value);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            Map<Long, List<MatchDetail>> result = new HashMap<>();
            for (Object[] row : rows) {
                Long id = ((Number) row[0]).longValue();
                StringBuilder pointer = new StringBuilder();
                int segIdx = 0;
                for (String seg : segments) {
                    if (seg.equals("[]")) {
                        int idx = ((Number) row[1 + segIdx]).intValue();
                        pointer.append("[").append(idx).append("]");
                        segIdx++;
                    } else {
                        if (pointer.length() > 0 && segIdx > 0) pointer.append(".");
                        pointer.append(seg);
                    }
                }
                String snippetJson = (String) row[1 + arrayCount];
                JsonNode snippet = safeReadTree(snippetJson);
                Object val = convertValue(row[2 + arrayCount]);
                result.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new MatchDetail(pointer.toString(), snippet, val));
            }
            return result;
        });
    }

    public Map<Long, List<MatchDetail>> getCompoundArrayMatches(
            List<Long> runIds, String arrayRoot, List<ResolvedFilter> arrayFilters, String combine) {
        return secured.execute(() -> {
            if (runIds.isEmpty() || arrayFilters.isEmpty()) return Map.of();

            String idsCsv = runIds.stream().map(String::valueOf).collect(Collectors.joining(","));

            List<ResolvedFilter> elementFilters = new ArrayList<>();
            for (ResolvedFilter f : arrayFilters) {
                int idx = f.getPath().indexOf("[]");
                String leafPart = f.getPath().substring(idx + 2);
                if (leafPart.startsWith(".")) leafPart = leafPart.substring(1);
                elementFilters.add(new ResolvedFilter(leafPart, f.getOp(), f.getValue()));
            }

            CompoundQueryBuilder.CompoundQuery cq = compoundQueryBuilder.buildElementPredicate(elementFilters, combine);

            String sql = """
                SELECT r.id,
                       (arr.idx - 1) AS array_index,
                       arr.elem            AS snippet,
                       arr.elem #> string_to_array(:leaf, ',') AS value
                FROM run r
                CROSS JOIN LATERAL jsonb_array_elements(r.payload #> string_to_array(:arrayPath, '.'))
                                    WITH ORDINALITY AS arr(elem, idx)
                WHERE r.id = ANY(string_to_array(:ids, ',')::bigint[])
                  AND r.latest = true
                  AND """ + cq.whereClause();

            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("ids", idsCsv);
            query.setParameter("arrayPath", arrayRoot);
            String representativeLeaf = elementFilters.get(0).getPath().replace(".", ",");
            query.setParameter("leaf", representativeLeaf);

            for (var entry : cq.params().entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            Map<Long, List<MatchDetail>> result = new HashMap<>();
            for (Object[] row : rows) {
                Long id = ((Number) row[0]).longValue();
                int arrayIdx = ((Number) row[1]).intValue();
                String snippetJson = (String) row[2];
                JsonNode snippet = safeReadTree(snippetJson);
                Object val = convertValue(row[3]);
                String leafSample = elementFilters.get(0).getPath().replace(",", ".");
                String pointer = arrayRoot + "[" + arrayIdx + "]." + leafSample;
                result.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new MatchDetail(pointer, snippet, val));
            }
            return result;
        });
    }

    // ---------------------------------------------------------------
    // Aggregate query
    // ---------------------------------------------------------------
    public List<Map<String, Object>> aggregate(String agg, String metric, String groupBy, String batch) {
        return secured.execute(() -> {
            Set<String> allowed = Set.of("AVG", "MAX", "MIN", "SUM", "COUNT");
            if (!allowed.contains(agg.toUpperCase())) {
                throw new IllegalArgumentException("Unsupported aggregate: " + agg + ". Allowed: " + allowed);
            }

            String metricExpr = agg.equalsIgnoreCase("COUNT") && (metric == null || metric.isBlank())
                    ? "*"
                    : "(r.payload #>> string_to_array(:metric, '.'))::numeric";

            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(agg.toUpperCase()).append("(").append(metricExpr).append(") AS result");

            if (groupBy != null && !groupBy.isBlank()) {
                sql.append(", (r.payload #>> string_to_array(:groupBy, '.')) AS group_val");
            }

            sql.append(" FROM run r WHERE r.latest = true");

            if (batch != null && !batch.isBlank()) {
                sql.append(" AND r.batch = :batch");
            }

            if (groupBy != null && !groupBy.isBlank()) {
                sql.append(" GROUP BY group_val ORDER BY result DESC");
            }

            Query query = entityManager.createNativeQuery(sql.toString());
            if (!agg.equalsIgnoreCase("COUNT") || (metric != null && !metric.isBlank())) {
                query.setParameter("metric", metric);
            }
            if (groupBy != null && !groupBy.isBlank()) {
                query.setParameter("groupBy", groupBy);
            }
            if (batch != null && !batch.isBlank()) {
                query.setParameter("batch", batch);
            }

            @SuppressWarnings("unchecked")
            List<Object> rows = query.getResultList();
            List<Map<String, Object>> results = new ArrayList<>();
            for (Object row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                if (groupBy != null && !groupBy.isBlank()) {
                    Object[] cols = (Object[]) row;
                    Number resultValue = (Number) cols[0];
                    entry.put("result", resultValue == null ? null : resultValue.doubleValue());
                    entry.put("group", cols[1] != null ? cols[1].toString() : null);
                } else {
                    Number resultValue = (Number) row;
                    entry.put("result", resultValue == null ? null : resultValue.doubleValue());
                }
                results.add(entry);
            }
            return results;
        });
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Resolve the team for a batch-mapping lookup.
     */
    private UUID currentTeamIdForMapping() {
        AppSecurityContext.UserPrincipal p = AppSecurityContext.require();
        if (p.teamId() == null) {
            throw new IllegalStateException(
                    "This operation requires a team. Supervisors must select a team.");
        }
        return p.teamId();
    }

    private String resolvePathInternal(String metric, String batch) {
        if (metric.contains(".") || metric.contains("[]")) {
            return metric;
        }
        if (batch != null && !batch.isBlank()) {
            Map<String, List<String>> mapping =
                    batchSchemaService.getOrCreateMapping(currentTeamIdForMapping(), batch);
            List<String> paths = mapping.get(metric);
            if (paths != null && !paths.isEmpty()) {
                return paths.get(0);
            }
        }
        return metric;
    }

    private static Pageable stripSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    private Page<Run> executeQuery(String path, String op, String value,
                                   String batch, Pageable pageable) {
        Pageable unsorted = stripSort(pageable);
        boolean hasBatch = (batch != null && !batch.isBlank());

        if (path.contains("[]")) {
            return executeArrayQuery(path, op, value, batch, unsorted);
        }

        return switch (op) {
            case "gt"  -> hasBatch
                    ? runRepository.findByMetricGreaterThanBatch(path, parseDouble(value), batch, unsorted)
                    : runRepository.findByMetricGreaterThan(path, parseDouble(value), unsorted);
            case "gte" -> hasBatch
                    ? runRepository.findByMetricGreaterThanOrEqualBatch(path, parseDouble(value), batch, unsorted)
                    : runRepository.findByMetricGreaterThanOrEqual(path, parseDouble(value), unsorted);
            case "lt"  -> hasBatch
                    ? runRepository.findByMetricLessThanBatch(path, parseDouble(value), batch, unsorted)
                    : runRepository.findByMetricLessThan(path, parseDouble(value), unsorted);
            case "lte" -> hasBatch
                    ? runRepository.findByMetricLessThanOrEqualBatch(path, parseDouble(value), batch, unsorted)
                    : runRepository.findByMetricLessThanOrEqual(path, parseDouble(value), unsorted);
            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsBatch(path, numericValue, batch, unsorted)
                            : runRepository.findByMetricEquals(path, numericValue, unsorted);
                } else {
                    yield hasBatch
                            ? runRepository.findByMetricEqualsTextBatch(path, value, batch, unsorted)
                            : runRepository.findByMetricEqualsText(path, value, unsorted);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported operator: " + op + ". Allowed: gt, gte, lt, lte, eq.");
        };
    }

    private Page<Run> executeArrayQuery(String path, String op, String value,
                                        String batch, Pageable pageable) {
        Pageable unsorted = stripSort(pageable);
        boolean hasBatch = (batch != null && !batch.isBlank());
        String jsonbPath = path.replace("[]", "[*]");
        int lastDot = jsonbPath.lastIndexOf('.');
        String leaf = jsonbPath.substring(lastDot + 1);
        String pathForJsonb = jsonbPath.substring(0, lastDot);

        return switch (op) {
            case "gt"  -> hasBatch
                    ? runRepository.findByArrayGreaterThanBatch(pathForJsonb, leaf, parseDouble(value), batch, unsorted)
                    : runRepository.findByArrayGreaterThan(pathForJsonb, leaf, parseDouble(value), unsorted);
            case "gte" -> hasBatch
                    ? runRepository.findByArrayGreaterThanOrEqualBatch(pathForJsonb, leaf, parseDouble(value), batch, unsorted)
                    : runRepository.findByArrayGreaterThanOrEqual(pathForJsonb, leaf, parseDouble(value), unsorted);
            case "lt"  -> hasBatch
                    ? runRepository.findByArrayLessThanBatch(pathForJsonb, leaf, parseDouble(value), batch, unsorted)
                    : runRepository.findByArrayLessThan(pathForJsonb, leaf, parseDouble(value), unsorted);
            case "lte" -> hasBatch
                    ? runRepository.findByArrayLessThanOrEqualBatch(pathForJsonb, leaf, parseDouble(value), batch, unsorted)
                    : runRepository.findByArrayLessThanOrEqual(pathForJsonb, leaf, parseDouble(value), unsorted);
            case "eq"  -> {
                Double numericValue = tryParseDouble(value);
                if (numericValue != null) {
                    yield hasBatch
                            ? runRepository.findByArrayEqualsBatch(pathForJsonb, leaf, numericValue, batch, unsorted)
                            : runRepository.findByArrayEquals(pathForJsonb, leaf, numericValue, unsorted);
                } else {
                    yield hasBatch
                            ? runRepository.findByArrayEqualsTextBatch(pathForJsonb, leaf, value, batch, unsorted)
                            : runRepository.findByArrayEqualsText(pathForJsonb, leaf, value, unsorted);
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

    private String buildArrayPredicate(String op, String value, String alias) {
        boolean numeric = isNumeric(value);
        String elemRef = alias + ".elem";
        return switch (op) {
            case "gt", "gte", "lt", "lte" -> {
                if (!numeric) throw new IllegalArgumentException("Operator " + op + " requires a numeric value");
                yield "(" + elemRef + " #>> string_to_array(:leafParts, ','))::numeric " +
                        opSymbol(op) + " :val";
            }
            case "eq" -> numeric
                    ? "(" + elemRef + " #>> string_to_array(:leafParts, ','))::numeric = :val"
                    : elemRef + " #>> string_to_array(:leafParts, ',') = :val";
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