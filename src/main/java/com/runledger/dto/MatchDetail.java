package com.runledger.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single exact match inside a run.
 * Contains a human‑friendly pointer, the JSON snippet (block‑0 parent),
 * and the matched value.
 */
public record MatchDetail(String pointer, JsonNode snippet, Object value) {}