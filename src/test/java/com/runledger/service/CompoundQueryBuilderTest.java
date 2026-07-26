package com.runledger.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CompoundQueryBuilderTest {

    private final CompoundQueryBuilder builder = new CompoundQueryBuilder();

    @Test
    void shouldBuildAndClauseForTwoScalarFilters() {
        ResolvedFilter f1 = new ResolvedFilter("accuracy", "gt", "0.9");
        ResolvedFilter f2 = new ResolvedFilter("loss", "lt", "0.2");
        CompoundQueryBuilder.CompoundQuery query = builder.build(List.of(f1, f2), "and");

        // Verify SQL structure
        assertThat(query.whereClause()).contains("AND");
        assertThat(query.whereClause()).startsWith("(");
        assertThat(query.whereClause()).endsWith(")");

        // Verify parameters contain the path strings and values
        assertThat(query.params()).containsEntry("p0", "accuracy");
        assertThat(query.params()).containsEntry("p2", "loss");
        assertThat(query.params().get("v1")).isEqualTo(0.9);
        assertThat(query.params().get("v3")).isEqualTo(0.2);
    }

    @Test
    void shouldBuildOrClause() {
        ResolvedFilter f1 = new ResolvedFilter("f1", "eq", "0.5");
        ResolvedFilter f2 = new ResolvedFilter("f1", "eq", "0.5");
        CompoundQueryBuilder.CompoundQuery query = builder.build(List.of(f1, f2), "or");
        assertThat(query.whereClause()).contains("OR");
    }

    @Test
    void shouldHandleArrayPathWithGt() {
        ResolvedFilter f = new ResolvedFilter("phases[].metrics.loss", "lt", "0.1");
        CompoundQueryBuilder.CompoundQuery query = builder.build(List.of(f), "and");

        // Verify SQL uses array-specific patterns
        assertThat(query.whereClause()).contains("EXISTS");
        assertThat(query.whereClause()).contains("jsonb_path_query");

        // Verify the array path was split correctly in parameters
        assertThat(query.params()).containsEntry("p0", "phases[*].metrics");
        assertThat(query.params()).containsEntry("l1", "loss");
        assertThat(query.params().get("v2")).isEqualTo(0.1);
    }

    @Test
    void shouldTreatEqAsNumericIfValueIsNumber() {
        ResolvedFilter f = new ResolvedFilter("accuracy", "eq", "0.95");
        CompoundQueryBuilder.CompoundQuery query = builder.build(List.of(f), "and");
        // Numeric cast should appear in the SQL
        assertThat(query.whereClause()).contains("::numeric");
    }

    @Test
    void shouldTreatEqAsTextIfValueIsNotNumber() {
        ResolvedFilter f = new ResolvedFilter("name", "eq", "adam");
        CompoundQueryBuilder.CompoundQuery query = builder.build(List.of(f), "and");
        // No numeric cast for text values
        assertThat(query.whereClause()).doesNotContain("::numeric");
    }
}