package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.dto.FieldDiff;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Flattens a JSON payload to a map of dot-paths to string values, and
 * diffs two payloads field by field.
 *
 * <p>Only changed fields are returned by {@link #diff}. Fields whose value
 * is identical in both versions are omitted — a diff that lists every
 * unchanged field alongside the changed ones is noise, not signal.
 *
 * <p>Array handling: paths use bracket indices ({@code results[2].accuracy}),
 * matching the array-element notation the query engine uses. A change in
 * array length produces additions or removals for the affected indices,
 * not a "the array changed" entry.
 */
@Service
public class PayloadFlattener {

    private final ObjectMapper objectMapper;

    public PayloadFlattener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Flatten a JSON payload to a map of dot-path → string value.
     * Throws {@link RuntimeException} if the payload can't be parsed;
     * callers should have already validated it.
     */
    public Map<String, String> flatten(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, String> out = new LinkedHashMap<>();
            flattenNode("", root, out);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to flatten payload", e);
        }
    }

    /**
     * Diff two payloads. Returns a list of only the fields that differ,
     * sorted alphabetically by path. Identical payloads produce an empty
     * list.
     */
    public List<FieldDiff> diff(String oldJson, String newJson) {
        Map<String, String> oldFlat = flatten(oldJson);
        Map<String, String> newFlat = flatten(newJson);

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(oldFlat.keySet());
        allPaths.addAll(newFlat.keySet());

        List<FieldDiff> diffs = new ArrayList<>();
        for (String path : allPaths) {
            String oldValue = oldFlat.get(path);
            String newValue = newFlat.get(path);

            if (Objects.equals(oldValue, newValue)) {
                continue;   // unchanged
            }

            FieldDiff.ChangeType type;
            if (oldValue == null) {
                type = FieldDiff.ChangeType.ADDED;
            } else if (newValue == null) {
                type = FieldDiff.ChangeType.REMOVED;
            } else {
                type = FieldDiff.ChangeType.CHANGED;
            }
            diffs.add(new FieldDiff(path, oldValue, newValue, type));
        }
        return diffs;
    }

    private void flattenNode(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, "null");
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty()
                        ? entry.getKey()
                        : prefix + "." + entry.getKey();
                flattenNode(key, entry.getValue(), out);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenNode(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.asText());
        }
    }
}