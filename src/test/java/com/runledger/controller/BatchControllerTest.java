package com.runledger.controller;

import com.runledger.dto.BatchSummary;
import com.runledger.security.IdentityFilter;
import com.runledger.security.SessionIdentityFilter;
import com.runledger.service.RunQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for BatchController. Route and JSON shape only; RLS behavior
 * is covered by RlsHttpIsolationTest.
 */
@WebMvcTest(BatchController.class)
@AutoConfigureMockMvc(addFilters = false)
class BatchControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RunQueryService runQueryService;
    @MockitoBean private IdentityFilter identityFilter;
    @MockitoBean private SessionIdentityFilter sessionIdentityFilter;

    @Test
    void list_returnsBatchesFromService() throws Exception {
        when(runQueryService.listBatches()).thenReturn(List.of(
                new BatchSummary(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "Team A", "demo-alice", 4, null)));

        mockMvc.perform(get("/api/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teamId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$[0].teamName").value("Team A"))
                .andExpect(jsonPath("$[0].batchName").value("demo-alice"))
                .andExpect(jsonPath("$[0].runCount").value(4));
    }
}