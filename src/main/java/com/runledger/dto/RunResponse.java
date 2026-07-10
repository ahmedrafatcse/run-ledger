package com.runledger.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record RunResponse(
        Long id,
        JsonNode payload,
        OffsetDateTime createdAt
) {}