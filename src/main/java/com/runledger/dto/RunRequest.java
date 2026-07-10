package com.runledger.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record RunRequest(
        @NotNull(message = "payload is required")
        JsonNode payload,

        // optional batch identifier, e.g. folder name
        String batch
) {}