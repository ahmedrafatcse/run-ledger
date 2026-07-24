package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
        // with a batch, the service calls the batch‑variant of the repository method
        when(runRepository.findByMetricGreaterThanBatch(
                eq("metrics.accuracy"), eq(0.95), eq("batch1"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", "batch1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanBatch("metrics.accuracy", 0.95, "batch1", Pageable.unpaged());
    }

    @Test
    void shouldTreatDotPathAsLiteralEvenWithBatch() {
        // dot‑path + batch → batch variant, path unchanged
        // Because the metric already contains a dot, the BatchSchemaService is never called.
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanBatch(
                eq("config.lr"), eq(0.001), eq("batch1"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("config.lr", "gt", "0.001", "batch1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanBatch("config.lr", 0.001, "batch1", Pageable.unpaged());
        // Verify that the batch schema service was never consulted
        verifyNoInteractions(batchSchemaService);
    }

    @Test
    void shouldReturnRawKeyWhenNoMappingExists() {
        when(batchSchemaService.getOrCreateMapping("batch1"))
                .thenReturn(Map.of());   // empty mapping

        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        // no mapping, batch present → batch variant with raw key
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
        // no batch → the metric is used as the path directly (non‑batch variant)
        when(runRepository.findByMetricGreaterThan(eq("accuracy"), eq(0.95), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThan("accuracy", 0.95, Pageable.unpaged());
    }
}