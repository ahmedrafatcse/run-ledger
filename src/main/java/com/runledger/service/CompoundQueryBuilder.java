package com.runledger.service;

import com.runledger.dto.Filter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CompoundQueryBuilder {

    private int paramCounter = 0;

    /**
     * Build a parameterised SQL WHERE clause and count clause for a list of
     * resolved filters. Each filter's path is already resolved (scalar or array).
     *
     * @param filters list of resolved filters (path, op, value)
     * @param combine "and" or "or"
     * @return a record containing the WHERE fragment, COUNT fragment, and the parameter map
     */
    public CompoundQuery build(List<ResolvedFilter> filters, String combine) {
        paramCounter = 0;
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> clauses = new ArrayList<>();

        for (ResolvedFilter f : filters) {
            if (f.getPath().contains("[]")) {
                clauses.add(buildArrayClause(f, params));
            } else {
                clauses.add(buildScalarClause(f, params));
            }
        }

        String conjunction = " " + combine.toUpperCase() + " ";
        String whereClause = clauses.stream().collect(Collectors.joining(conjunction, "(", ")"));

        // The count query uses the same WHERE clause
        String countWhere = whereClause;

        return new CompoundQuery(whereClause, countWhere, params);
    }

    private String buildScalarClause(ResolvedFilter f, Map<String, Object> params) {
        String pathParam = "p" + paramCounter++;
        params.put(pathParam, f.getPath());

        return switch (f.getOp()) {
            case "gt"  -> {
                String v = "v" + paramCounter++;
                params.put(v, Double.parseDouble(f.getValue()));
                yield "(r.payload #>> string_to_array(:" + pathParam + ", '.'))::numeric > :" + v;
            }
            case "gte" -> {
                String v = "v" + paramCounter++;
                params.put(v, Double.parseDouble(f.getValue()));
                yield "(r.payload #>> string_to_array(:" + pathParam + ", '.'))::numeric >= :" + v;
            }
            case "lt"  -> {
                String v = "v" + paramCounter++;
                params.put(v, Double.parseDouble(f.getValue()));
                yield "(r.payload #>> string_to_array(:" + pathParam + ", '.'))::numeric < :" + v;
            }
            case "lte" -> {
                String v = "v" + paramCounter++;
                params.put(v, Double.parseDouble(f.getValue()));
                yield "(r.payload #>> string_to_array(:" + pathParam + ", '.'))::numeric <= :" + v;
            }
            case "eq"  -> {
                // Try numeric first, fallback to text
                Double num = tryParseDouble(f.getValue());
                if (num != null) {
                    String v = "v" + paramCounter++;
                    params.put(v, num);
                    yield "(r.payload #>> string_to_array(:" + pathParam + ", '.'))::numeric = :" + v;
                } else {
                    String v = "v" + paramCounter++;
                    params.put(v, f.getValue());
                    yield "r.payload #>> string_to_array(:" + pathParam + ", '.') = :" + v;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported operator: " + f.getOp());
        };
    }

    private String buildArrayClause(ResolvedFilter f, Map<String, Object> params) {
        String path = f.getPath();
        // Replace [] with [*] for jsonb_path_query
        String jsonbPath = path.replace("[]", "[*]");
        int lastDot = jsonbPath.lastIndexOf('.');
        String leaf = jsonbPath.substring(lastDot + 1);
        String arrayPath = jsonbPath.substring(0, lastDot);

        String pathParam = "p" + paramCounter++;
        params.put(pathParam, arrayPath);
        String leafParam = "l" + paramCounter++;
        params.put(leafParam, leaf);

        return switch (f.getOp()) {
            case "gt", "gte", "lt", "lte", "eq" -> {
                String v = "v" + paramCounter++;
                Double num = tryParseDouble(f.getValue());
                if (num != null) {
                    params.put(v, num);
                    String numericGuard = "jsonb_typeof(elem #> string_to_array(:" + leafParam + ", '.')) = 'number'";
                    String comparison = "(elem #>> string_to_array(:" + leafParam + ", '.'))::numeric " +
                            opSymbol(f.getOp()) + " :" + v;
                    yield "EXISTS (SELECT 1 FROM jsonb_path_query(r.payload, ('$.' || :" + pathParam +
                            ")::jsonpath) AS elem WHERE " + numericGuard + " AND " + comparison + ")";
                } else {
                    // text equality
                    params.put(v, f.getValue());
                    yield "EXISTS (SELECT 1 FROM jsonb_path_query(r.payload, ('$.' || :" + pathParam +
                            ")::jsonpath) AS elem WHERE elem #>> string_to_array(:" + leafParam +
                            ", '.') = :" + v + ")";
                }
            }
            default -> throw new IllegalArgumentException("Unsupported operator: " + f.getOp());
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

    private Double tryParseDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Holds the built SQL fragments and parameter map.
     */
    public record CompoundQuery(String whereClause, String countWhere, Map<String, Object> params) {}
}