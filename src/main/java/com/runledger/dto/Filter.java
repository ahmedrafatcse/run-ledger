package com.runledger.dto;

import jakarta.validation.constraints.NotBlank;

public record Filter(
        @NotBlank String metric,
        @NotBlank String op,
        @NotBlank String value
) {}