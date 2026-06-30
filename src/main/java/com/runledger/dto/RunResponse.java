package com.runledger.dto;

// sending/receiving data through API, what client gets to send to backend and what the backend gets to return to client

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

// automatically gives us a constructor, getters, equals(), hashCode(), and toString(). Perfect for a simple data carrier.
public record RunResponse(
        Long id,
        JsonNode payload,
        LocalDateTime createdAt
) {}