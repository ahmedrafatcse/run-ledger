package com.runledger.controller;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import com.runledger.service.RunQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RunRepository runRepository;
    @MockitoBean private RunQueryService runQueryService;

    private Run run(Long id, String payloadJson) {
        Run r = new Run();
        r.setId(id);
        r.setPayload(payloadJson);
        r.setCreatedAt(OffsetDateTime.parse("2026-07-08T15:38:39+06:00"));
        return r;
    }

    @Test
    void postValidRun_shouldReturn201() throws Exception {
        Run saved = run(1L, "{\"accuracy\":0.95}");
        when(runRepository.save(any(Run.class))).thenReturn(saved);

        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"accuracy\":0.95}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.payload.accuracy").value(0.95));
    }

    @Test
    void postMissingPayload_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"));
    }

    @Test
    void getRunById_shouldReturn200() throws Exception {
        Run found = run(1L, "{\"accuracy\":0.95}");
        when(runRepository.findById(1L)).thenReturn(Optional.of(found));

        mockMvc.perform(get("/api/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getRunById_notFound_shouldReturn404() throws Exception {
        when(runRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/runs/999"))
                .andExpect(status().isNotFound());
    }

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
    void searchRuns_invalidOperator_shouldReturn400() throws Exception {
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
                .andExpect(status().isBadRequest());
    }
}