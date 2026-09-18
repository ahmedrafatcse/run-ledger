package com.runledger.controller;

import com.runledger.config.SecurityConfig;
import com.runledger.dto.MatchDetail;
import com.runledger.dto.RunRequest;
import com.runledger.entity.Run;
import com.runledger.exception.GlobalExceptionHandler;
import com.runledger.repository.RunRepository;
import com.runledger.security.IdentityFilter;
import com.runledger.service.RunIngestionService;
import com.runledger.service.RunQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class RunControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RunRepository runRepository;
    @MockitoBean private RunQueryService runQueryService;
    @MockitoBean private RunIngestionService ingestionService;
    @MockitoBean private IdentityFilter identityFilter;

    private Run run(Long id, String payloadJson) {
        Run r = new Run();
        r.setId(id);
        r.setPayload(payloadJson);
        r.setCreatedAt(OffsetDateTime.parse("2026-07-08T15:38:39+06:00"));
        return r;
    }

    // ---------- POST /api/runs ----------
    @Test
    void postValidRun_shouldReturn201() throws Exception {
        Run saved = run(1L, "{\"accuracy\":0.95}");
        when(ingestionService.ingest(any(RunRequest.class))).thenReturn(saved);

        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"accuracy\":0.95}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.payload.accuracy").value(0.95))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void postMissingPayload_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("payload")));
    }

    // ---------- GET /api/runs/{id} ----------
    @Test
    void getRunById_shouldReturn200() throws Exception {
        Run found = run(1L, "{\"accuracy\":0.95}");
        when(runRepository.findById(1L)).thenReturn(Optional.of(found));

        mockMvc.perform(get("/api/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.payload.accuracy").value(0.95));
    }

    @Test
    void getRunById_notFound_shouldReturn404() throws Exception {
        when(runRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/runs/999"))
                .andExpect(status().isNotFound());
    }

    // ---------- Unified GET /api/runs (block search or batch listing) ----------
    @Test
    void searchRuns_validParams_shouldReturnPageOfRuns() throws Exception {
        Run r1 = run(1L, "{\"accuracy\":0.95}");
        Run r2 = run(2L, "{\"accuracy\":0.91}");
        List<Run> runs = List.of(r1, r2);
        Page<Run> page = new PageImpl<>(runs, Pageable.unpaged(), runs.size());

        when(runQueryService.queryByMetric(eq("accuracy"), eq("gt"), eq("0.9"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[1].id").value(2));
    }

    @Test
    void searchRuns_withBatch_shouldReturnPageOfRuns() throws Exception {
        Run r1 = run(1L, "{\"accuracy\":0.95}");
        List<Run> runs = List.of(r1);
        Page<Run> page = new PageImpl<>(runs, Pageable.unpaged(), runs.size());

        when(runQueryService.queryByMetric(eq("accuracy"), eq("gt"), eq("0.9"), eq("sweep-X"), any(Pageable.class)))
                .thenReturn(page);
        when(runQueryService.resolvePath(eq("accuracy"), eq("sweep-X")))
                .thenReturn("accuracy");

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "sweep-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    void searchRuns_invalidOperator_shouldReturn400() throws Exception {
        when(runQueryService.queryByMetric(eq("accuracy"), eq("invalid"), eq("0.9"), any(Pageable.class)))
                .thenThrow(new IllegalArgumentException("Unsupported operator: invalid"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "invalid")
                        .param("value", "0.9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRuns_missingMetric_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("metric")));
    }

    // ---------- Full‑text phrase search ----------
    @Test
    void searchRuns_fullTextPhrase_shouldReturnPageOfRuns() throws Exception {
        Run r1 = run(1L, "{\"accuracy\":0.95}");
        List<Run> runs = List.of(r1);
        Page<Run> page = new PageImpl<>(runs, Pageable.unpaged(), runs.size());

        when(runQueryService.searchByPhrase(eq("weight averaging"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/runs")
                        .param("q", "weight averaging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    // ---------- Fuzzy search ----------
    @Test
    void searchRuns_fuzzy_shouldReturnPageOfRuns() throws Exception {
        Run r1 = run(1L, "{\"accuracy\":0.95}");
        List<Run> runs = List.of(r1);
        Page<Run> page = new PageImpl<>(runs, Pageable.unpaged(), runs.size());

        when(runQueryService.searchByFuzzy(eq("weight avaraging"), eq(0.3), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/runs")
                        .param("q", "weight avaraging")
                        .param("fuzzy", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    // ---------- Metric key discovery ----------
    @Test
    void getMetrics_withBatch_shouldReturnMetricKeys() throws Exception {
        when(runQueryService.getAvailableMetrics("sweep-X")).thenReturn(List.of("acc", "loss"));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "sweep-X"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0]").value("acc"))
                .andExpect(jsonPath("$[1]").value("loss"));
    }

    // ---------- Multi‑condition AND/OR search ----------
    @Test
    void shouldReturnMatchingRunsForMultiFilterAnd() throws Exception {
        Run run = new Run();
        run.setId(1L);
        run.setPayload("{\"accuracy\":0.92, \"loss\":0.18}");
        run.setBatch("test-batch");
        run.setCreatedAt(OffsetDateTime.parse("2026-07-08T15:38:39+06:00"));
        List<Run> runs = List.of(run);
        Page<Run> page = new PageImpl<>(runs, Pageable.unpaged(), runs.size());

        when(runQueryService.queryByMultipleFilters(any(), any(Pageable.class)))
                .thenReturn(page);
        when(runQueryService.resolvePath(eq("accuracy"), eq("test-batch"))).thenReturn("accuracy");
        when(runQueryService.resolvePath(eq("loss"), eq("test-batch"))).thenReturn("loss");

        String requestBody = """
        {
          "filters": [
            {"metric": "accuracy", "op": "gt", "value": "0.9"},
            {"metric": "loss", "op": "lt", "value": "0.2"}
          ],
          "combine": "and",
          "batch": "test-batch",
          "pointers": false
        }
        """;

        mockMvc.perform(post("/api/runs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].payload.loss").value(0.18));
    }

    // ---------- Pointer tests ----------
    @Test
    void singleMetricGetWithPointers_shouldReturnMatchedField() throws Exception {
        Run run = run(1L, "{\"accuracy\":0.95}");
        Page<Run> page = new PageImpl<>(List.of(run), Pageable.unpaged(), 1);
        when(runQueryService.queryByMetric(eq("accuracy"), eq("gt"), eq("0.9"), any(Pageable.class)))
                .thenReturn(page);
        when(runQueryService.getScalarMatch(eq(run), eq("accuracy"), eq("gt"), eq("0.9")))
                .thenReturn(List.of(new MatchDetail("accuracy", null, 0.95)));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("pointers", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matched[0].pointer").value("accuracy"))
                .andExpect(jsonPath("$.content[0].matched[0].value").value(0.95));
    }

    @Test
    void singleMetricGetWithPointersFalse_shouldOmitMatchedField() throws Exception {
        Run run = run(1L, "{\"accuracy\":0.95}");
        Page<Run> page = new PageImpl<>(List.of(run), Pageable.unpaged(), 1);
        when(runQueryService.queryByMetric(eq("accuracy"), eq("gt"), eq("0.9"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("pointers", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matched").doesNotExist());
    }

    @Test
    void compoundSearchSameArray_shouldReturnPointers() throws Exception {
        Run run = run(1L, "{\"results\":[{\"sst2_cacc\":0.95,\"fin_asr\":0.5}]}");
        run.setBatch("defense");
        Page<Run> page = new PageImpl<>(List.of(run), Pageable.unpaged(), 1);
        when(runQueryService.queryByMultipleFilters(any(), any(Pageable.class)))
                .thenReturn(page);

        when(runQueryService.resolvePath(eq("results[].sst2_cacc"), eq("defense")))
                .thenReturn("results[].sst2_cacc");
        when(runQueryService.resolvePath(eq("results[].fin_asr"), eq("defense")))
                .thenReturn("results[].fin_asr");

        Map<Long, List<MatchDetail>> matchMap = Map.of(1L, List.of(
                new MatchDetail("results[0].sst2_cacc", null, 0.95)
        ));
        when(runQueryService.getCompoundArrayMatches(
                anyList(), eq("results"), anyList(), eq("and")))
                .thenReturn(matchMap);

        String requestBody = """
        {
          "filters": [
            {"metric": "results[].sst2_cacc", "op": "gt", "value": "0.9"},
            {"metric": "results[].fin_asr", "op": "gt", "value": "0.4"}
          ],
          "combine": "and",
          "batch": "defense"
        }
        """;

        mockMvc.perform(post("/api/runs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matched[0].pointer").value("results[0].sst2_cacc"));
    }

    @Test
    void compoundSearchDifferentArrays_shouldNotReturnPointers() throws Exception {
        Run run = run(1L, "{\"results\":[{\"sst2_cacc\":0.95}],\"phases\":[{\"loss\":0.1}]}");
        run.setBatch("defense");
        Page<Run> page = new PageImpl<>(List.of(run), Pageable.unpaged(), 1);
        when(runQueryService.queryByMultipleFilters(any(), any(Pageable.class)))
                .thenReturn(page);

        when(runQueryService.resolvePath(eq("results[].sst2_cacc"), eq("defense")))
                .thenReturn("results[].sst2_cacc");
        when(runQueryService.resolvePath(eq("phases[].loss"), eq("defense")))
                .thenReturn("phases[].loss");

        String requestBody = """
        {
          "filters": [
            {"metric": "results[].sst2_cacc", "op": "gt", "value": "0.9"},
            {"metric": "phases[].loss", "op": "lt", "value": "0.2"}
          ],
          "combine": "and",
          "batch": "defense"
        }
        """;

        mockMvc.perform(post("/api/runs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matched").doesNotExist());
    }

    // ---------- Mixed scalar + array compound test ----------
    @Test
    void compoundSearchMixedScalarAndArray_shouldReturnOnlyArrayPointers() throws Exception {
        Run run = run(1L, "{\"accuracy\":0.94,\"results\":[{\"sst2_cacc\":0.92,\"threshold\":0.8}]}");
        run.setBatch("defense");
        Page<Run> page = new PageImpl<>(List.of(run), Pageable.unpaged(), 1);
        when(runQueryService.queryByMultipleFilters(any(), any(Pageable.class)))
                .thenReturn(page);

        when(runQueryService.resolvePath(eq("accuracy"), eq("defense"))).thenReturn("accuracy");
        when(runQueryService.resolvePath(eq("results[].sst2_cacc"), eq("defense")))
                .thenReturn("results[].sst2_cacc");

        Map<Long, List<MatchDetail>> matchMap = Map.of(1L, List.of(
                new MatchDetail("results[0].sst2_cacc", null, 0.92)
        ));
        when(runQueryService.getCompoundArrayMatches(
                anyList(), eq("results"), anyList(), eq("and")))
                .thenReturn(matchMap);

        String requestBody = """
        {
          "filters": [
            {"metric": "accuracy", "op": "gt", "value": "0.9"},
            {"metric": "results[].sst2_cacc", "op": "gt", "value": "0.9"}
          ],
          "combine": "and",
          "batch": "defense"
        }
        """;

        mockMvc.perform(post("/api/runs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].matched[0].pointer").value("results[0].sst2_cacc"))
                .andExpect(jsonPath("$.content[0].matched.length()").value(1));
    }
}