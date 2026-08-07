package com.runledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.runledger.dto.Filter;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.MultiFilterRequest;
import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock
    private EntityManager entityManager;

    private ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @InjectMocks
    private RunQueryService runQueryService;

    @BeforeEach
    void setUp() {
        runQueryService = new RunQueryService(runRepository, batchSchemaService,
                compoundQueryBuilder, entityManager, objectMapper);
    }

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
    // Multi‑filter AND/OR search
    // ---------------------------------------------------------------

    @Test
    void shouldCallCustomRepositoryForMultiFilter() {
        MultiFilterRequest request = new MultiFilterRequest(
                List.of(new Filter("accuracy", "gt", "0.9"), new Filter("loss", "lt", "0.2")),
                "and",
                "test-batch",
                null,
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
        verify(runRepository, times(1)).findByCompoundFilter(anyString(), anyMap(), anyString(), any(Pageable.class));
    }

    // ---------------------------------------------------------------
    // Pointer localisation tests
    // ---------------------------------------------------------------

    @Test
    void getScalarMatch_shouldReturnMatchDetailForMatchingScalar() {
        Run run = new Run();
        run.setPayload("{\"accuracy\":0.95, \"loss\":0.12}");
        List<MatchDetail> matches = runQueryService.getScalarMatch(run, "accuracy", "gt", "0.9");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).pointer()).isEqualTo("accuracy");
        assertThat((Double) matches.get(0).value()).isEqualTo(0.95);
        assertThat(matches.get(0).snippet()).isNotNull();
    }

    @Test
    void getScalarMatch_shouldReturnEmptyForNonMatchingScalar() {
        Run run = new Run();
        run.setPayload("{\"accuracy\":0.85}");
        List<MatchDetail> matches = runQueryService.getScalarMatch(run, "accuracy", "gt", "0.9");

        assertThat(matches).isEmpty();
    }

    @Test
    void getScalarMatch_shouldReturnEmptyForMissingPath() {
        Run run = new Run();
        run.setPayload("{\"accuracy\":0.95}");
        List<MatchDetail> matches = runQueryService.getScalarMatch(run, "loss", "lt", "0.2");

        assertThat(matches).isEmpty();
    }

    @Test
    void getArrayMatches_shouldReturnCorrectPointers() {
        // Mock EntityManager native query
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);

        String snippetJson = "{\"budget\":0.01,\"method\":\"proxy\",\"sst2_cacc\":0.92}";
        Object[] row = new Object[]{1L, 3L, snippetJson, 0.92};
        List<Object[]> rows = List.<Object[]>of(row);
        when(mockQuery.getResultList()).thenReturn(rows);

        // Updated to 4-argument call – full path, op, value
        Map<Long, List<MatchDetail>> result = runQueryService.getArrayMatches(
                List.of(1L), "results[].sst2_cacc", "gt", "0.9");

        assertThat(result).containsKey(1L);
        List<MatchDetail> details = result.get(1L);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).pointer()).isEqualTo("results[3].sst2_cacc");
        assertThat(details.get(0).value()).isEqualTo(0.92);
        assertThat(details.get(0).snippet()).isNotNull();
    }

    @Test
    void getArrayMatches_shouldHandleEmptyInput() {
        // Updated to 4-argument call
        Map<Long, List<MatchDetail>> result = runQueryService.getArrayMatches(
                List.of(), "results[].sst2_cacc", "gt", "0.9");
        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------
    // Aggregate tests
    // ---------------------------------------------------------------

    @Test
    void aggregate_shouldReturnGroupedResults() {
        Query queryMock = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(queryMock);
        when(queryMock.setParameter(anyString(), any())).thenReturn(queryMock);

        List<Object[]> rows = List.of(
                new Object[]{0.95, "A"},
                new Object[]{0.88, "B"}
        );
        when(queryMock.getResultList()).thenReturn(rows);

        List<Map<String, Object>> results = runQueryService.aggregate("AVG", "accuracy", "experiment", null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("result")).isEqualTo(0.95);
        assertThat(results.get(0).get("group")).isEqualTo("A");
        assertThat(results.get(1).get("result")).isEqualTo(0.88);
        assertThat(results.get(1).get("group")).isEqualTo("B");
    }

    @Test
    void aggregate_withoutGroupBy_shouldReturnSingleRow() {
        Query queryMock = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(queryMock);
        when(queryMock.setParameter(anyString(), any())).thenReturn(queryMock);

        List<Object> rows = List.of(0.92);
        when(queryMock.getResultList()).thenReturn(rows);

        List<Map<String, Object>> results = runQueryService.aggregate("MAX", "accuracy", null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("result")).isEqualTo(0.92);
        assertThat(results.get(0)).doesNotContainKey("group");
    }
}