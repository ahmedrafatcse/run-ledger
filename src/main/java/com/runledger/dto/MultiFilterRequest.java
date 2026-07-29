package com.runledger.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MultiFilterRequest(
        @Size(min = 1, message = "At least one filter is required")
        List<Filter> filters,

        @Pattern(regexp = "^(and|or)$", message = "combine must match \"^(and|or)$\"")
        String combine,

        String batch,

        Integer block,

        // New: control whether to include match pointers (default true)
        Boolean pointers
) {}