package com.runledger.service;

import lombok.Getter;

@Getter
public class ResolvedFilter {
    private final String path;
    private final String op;
    private final String value;

    public ResolvedFilter(String path, String op, String value) {
        this.path = path;
        this.op = op;
        this.value = value;
    }
}