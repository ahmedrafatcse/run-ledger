package com.runledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record MultiFilterRequest(
        @NotEmpty List<@Valid Filter> filters,
        @Pattern(regexp = "^(and|or)$") String combine,
        String batch,
        Integer block
) {}