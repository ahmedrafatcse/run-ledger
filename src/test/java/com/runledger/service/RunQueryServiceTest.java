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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunQueryServiceTest {

    @Mock
    private RunRepository runRepository;

    @InjectMocks
    private RunQueryService runQueryService;

    // ---------------------------------------------------------------
    // Greater than / Greater than or equal
    // ---------------------------------------------------------------

    @Test
    void shouldCallGreaterThanRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThan(eq("accuracy"), eq(0.95), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "gt", "0.95", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThan("accuracy", 0.95, Pageable.unpaged());
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void shouldCallGreaterThanOrEqualRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricGreaterThanOrEqual(eq("loss"), eq(0.15), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("loss", "gte", "0.15", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricGreaterThanOrEqual("loss", 0.15, Pageable.unpaged());
    }

    // ---------------------------------------------------------------
    // Less than / Less than or equal
    // ---------------------------------------------------------------

    @Test
    void shouldCallLessThanRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricLessThan(eq("asr"), eq(0.1), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("asr", "lt", "0.1", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricLessThan("asr", 0.1, Pageable.unpaged());
    }

    @Test
    void shouldCallLessThanOrEqualRepositoryMethod() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricLessThanOrEqual(eq("asr"), eq(0.05), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("asr", "lte", "0.05", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricLessThanOrEqual("asr", 0.05, Pageable.unpaged());
    }

    // ---------------------------------------------------------------
    // Equals – numeric and text
    // ---------------------------------------------------------------

    @Test
    void shouldCallNumericEqualsWhenValueIsNumber() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricEquals(eq("accuracy"), eq(0.94), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("accuracy", "eq", "0.94", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricEquals("accuracy", 0.94, Pageable.unpaged());
        verify(runRepository, never()).findByMetricEqualsText(any(), any(), any());
    }

    @Test
    void shouldCallTextEqualsWhenValueIsNotNumber() {
        var dummyRuns = List.of(new Run());
        Page<Run> dummyPage = new PageImpl<>(dummyRuns, Pageable.unpaged(), dummyRuns.size());
        when(runRepository.findByMetricEqualsText(eq("status"), eq("completed"), any(Pageable.class)))
                .thenReturn(dummyPage);

        Page<Run> result = runQueryService.queryByMetric("status", "eq", "completed", Pageable.unpaged());

        assertThat(result.getContent()).isEqualTo(dummyRuns);
        verify(runRepository).findByMetricEqualsText("status", "completed", Pageable.unpaged());
        verify(runRepository, never()).findByMetricEquals(any(), anyDouble(), any());
    }

    // ---------------------------------------------------------------
    // Invalid operator
    // ---------------------------------------------------------------

    @Test
    void shouldThrowForInvalidOperator() {
        assertThatThrownBy(() -> runQueryService.queryByMetric("accuracy", "bad", "0.9", Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported operator");
    }

    // ---------------------------------------------------------------
    // Invalid numeric value for numeric operators
    // ---------------------------------------------------------------

    @Test
    void shouldThrowWhenNumericValueIsMissing() {
        assertThatThrownBy(() -> runQueryService.queryByMetric("accuracy", "gt", "abc", Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a numeric value");
    }
}