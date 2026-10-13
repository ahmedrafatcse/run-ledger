package com.runledger.dto;

import java.util.UUID;

/** A team the current identity can pick from. */
public record TeamOption(UUID id, String name) {}