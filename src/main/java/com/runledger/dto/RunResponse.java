package com.runledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunResponse {
    private Long id;
    private JsonNode payload;
    private OffsetDateTime createdAt;
    private List<MatchDetail> matched;   // NEW

    // existing constructor
    public RunResponse(Long id, JsonNode payload, OffsetDateTime createdAt) {
        this.id = id;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    // NEW constructor with matched
    public RunResponse(Long id, JsonNode payload, OffsetDateTime createdAt,
                       List<MatchDetail> matched) {
        this(id, payload, createdAt);
        this.matched = matched;
    }

    // getters & setters (if not using records)
    public Long getId() { return id; }
    public JsonNode getPayload() { return payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<MatchDetail> getMatched() { return matched; }

    public void setId(Long id) { this.id = id; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setMatched(List<MatchDetail> matched) { this.matched = matched; }
}