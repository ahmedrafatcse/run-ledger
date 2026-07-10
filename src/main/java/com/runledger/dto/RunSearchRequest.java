package com.runledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RunSearchRequest(
        @NotBlank(message = "metric is required")
        String metric,

        @NotBlank(message = "op is required")
        @Pattern(regexp = "^(gt|gte|lt|lte|eq)$", message = "op must be one of: gt, gte, lt, lte, eq")
        String op,

        @NotBlank(message = "value is required")
        String value
) {}