package com.runledger.dto;

/**
 * A single field's difference between two versions of a run's payload.
 *
 * <p>Field paths are dot-paths into the flattened payload, with array
 * indices in brackets (e.g. {@code results[2].accuracy}). Values are
 * stringified; the diff is textual, not typed.
 */
public record FieldDiff(String path, String oldValue, String newValue, ChangeType type) {

    public enum ChangeType {
        CHANGED,
        ADDED,
        REMOVED
    }

    public boolean isAdded()   { return type == ChangeType.ADDED; }
    public boolean isRemoved() { return type == ChangeType.REMOVED; }
}