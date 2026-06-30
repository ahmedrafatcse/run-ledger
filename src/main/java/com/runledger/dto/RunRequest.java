package com.runledger.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RunRequest(
        JsonNode payload
) {}