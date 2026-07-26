package com.runledger.service;

import com.runledger.dto.Filter;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunQueryServiceTest {

    @Mock
    private RunRepository runRepository;

    @Mock
    private BatchSchemaService batchSchemaService;

    @Mock
    private CompoundQueryBuilder compoundQueryBuilder;

    @InjectMocks
    private RunQueryService runQueryService;

    // ---------------------------------------------------------------
    // Greater than / Greater than or equal
    // ---------------------------------------------------------------

    @Test
    void shouldCallGreaterThanRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThan(eq("metrics.accuracy"), eq(0.95), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.accuracy", "gt", "0.95", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThan("metrics.accuracy", 0.95, Pageable.unpaged());
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void shouldCallGreaterThanOrEqualRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanOrEqual(eq("metrics.loss"), eq(0.15), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.loss", "gte", "0.15", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanOrEqual("metrics.loss", 0.15, Pageable.unpaged());
    }

    // ---------------------------------------------------------------
    // Less than / Less than or equal
    // ---------------------------------------------------------------

    @Test
    void shouldCallLessThanRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricLessThan(eq("metrics.asr"), eq(0.1), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.asr", "lt", "0.1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricLessThan("metrics.asr", 0.1, Pageable.unpaged());
    }

    @Test
    void shouldCallLessThanOrEqualRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricLessThanOrEqual(eq("metrics.asr"), eq(0.05), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.asr", "lte", "0.05", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricLessThanOrEqual("metrics.asr", 0.05, Pageable.unpaged());
    }

    // ---------------------------------------------------------------
    // Equals – numeric and text
    // ---------------------------------------------------------------

    @Test
    void shouldCallNumericEqualsWhenValueIsNumber() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricEquals(eq("metrics.accuracy"), eq(0.94), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.accuracy", "eq", "0.94", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricEquals("metrics.accuracy", 0.94, Pageable.unpaged());
        verify(runRepository, never()).findByMetricEqualsText(any(), any(), any());
    }

    @Test
    void shouldCallTextEqualsWhenValueIsNotNumber() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricEqualsText(eq("metrics.status"), eq("completed"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("metrics.status", "eq", "completed", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricEqualsText("metrics.status", "completed", Pageable.unpaged());
        verify(runRepository, never()).findByMetricEquals(any(), anyDouble(), any());
    }

    // ---------------------------------------------------------------
    // Invalid operator
    // ---------------------------------------------------------------

    @Test
    void shouldThrowForInvalidOperator() {
        assertThatThrownBy(() -> runQueryService.queryByMetric("metrics.accuracy", "bad", "0.9", Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported operator");
    }

    // ---------------------------------------------------------------
    // Invalid numeric value for numeric operators
    // ---------------------------------------------------------------

    @Test
    void shouldThrowWhenNumericValueIsMissing() {
        assertThatThrownBy(() -> runQueryService.queryByMetric("metrics.accuracy", "gt", "abc", Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a numeric value");
    }

    // ---------------------------------------------------------------
    // Shorthand resolution tests (batch‑variant repository methods)
    // ---------------------------------------------------------------

    @Test
    void shouldResolveShorthandKeyUsingBatchMapping() {
        when(batchSchemaService.getOrCreateMapping("batch1"))
                .thenReturn(Map.of("accuracy", "metrics.accuracy"));

        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanBatch(
                eq("metrics.accuracy"), eq(0.95), eq("batch1"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", "batch1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanBatch("metrics.accuracy", 0.95, "batch1", Pageable.unpaged());
    }

    @Test
    void shouldTreatDotPathAsLiteralEvenWithBatch() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanBatch(
                eq("config.lr"), eq(0.001), eq("batch1"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("config.lr", "gt", "0.001", "batch1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanBatch("config.lr", 0.001, "batch1", Pageable.unpaged());
        verifyNoInteractions(batchSchemaService);
    }

    @Test
    void shouldReturnRawKeyWhenNoMappingExists() {
        when(batchSchemaService.getOrCreateMapping("batch1"))
                .thenReturn(Map.of());

        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanBatch(
                eq("accuracy"), eq(0.95), eq("batch1"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", "batch1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanBatch("accuracy", 0.95, "batch1", Pageable.unpaged());
    }

    @Test
    void shouldReturnRawKeyWhenBatchIsNull() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThan(eq("accuracy"), eq(0.95), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThan("accuracy", 0.95, Pageable.unpaged());
    }

    // ---------------------------------------------------------------
    // Multi‑filter AND/OR search (corrected)
    // ---------------------------------------------------------------

    @Test
    void shouldCallCustomRepositoryForMultiFilter() {
        MultiFilterRequest request = new MultiFilterRequest(
                List.of(new Filter("accuracy", "gt", "0.9"), new Filter("loss", "lt", "0.2")),
                "and",
                "test-batch",
                null
        );

        when(batchSchemaService.getOrCreateMapping("test-batch"))
                .thenReturn(Map.of("accuracy", "accuracy", "loss", "metrics.loss"));

        HashMap<String, Object> mutableParams = new HashMap<>();
        mutableParams.put("p0", "accuracy");
        mutableParams.put("v0", 0.9);
        CompoundQueryBuilder.CompoundQuery dummyQuery = new CompoundQueryBuilder.CompoundQuery(
                "dummy WHERE clause",
                "dummy count WHERE",
                mutableParams
        );
        when(compoundQueryBuilder.build(anyList(), eq("and"))).thenReturn(dummyQuery);

        Page<Run> mockPage = new PageImpl<>(List.of(new Run()));
        when(runRepository.findByCompoundFilter(anyString(), anyMap(), anyString(), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<Run> result = runQueryService.queryByMultipleFilters(request, PageRequest.of(0, 10));

        assertThat(result).isNotEmpty();
        // Verify the custom repository method was invoked
        verify(runRepository, times(1)).findByCompoundFilter(anyString(), anyMap(), anyString(), any(Pageable.class));
    }
}