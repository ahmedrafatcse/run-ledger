package com.runledger;

import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class RunLedgerApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("runledger")
            .withUsername("runledger")
            .withPassword("runledger");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RunRepository runRepository;

    @BeforeEach
    void setUp() throws Exception {
        runRepository.deleteAll();
    }

    // ---------------------------------------------------------------
    // Helper for green console output
    // ---------------------------------------------------------------
    private void greenPrint(String message) {
        System.out.println("\u001B[32m" + message + "\u001B[0m");
    }

    // ================================================================
    // SINGLE RUN TESTS
    // ================================================================
    @Test
    void singleRunIngestion_shouldReturn201() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"accuracy\":0.88}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.payload.accuracy").value(0.88))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        greenPrint("Single run ingestion --- SUCCESS");
    }

    @Test
    void missingPayload_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("payload")));
        greenPrint("Missing payload rejection --- SUCCESS");
    }

    @Test
    void singleRunRetrieval_shouldReturn200_and404() throws Exception {
        String response = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"accuracy\":0.95}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        mockMvc.perform(get("/api/runs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(get("/api/runs/99999"))
                .andExpect(status().isNotFound());
        greenPrint("Run retrieval (200 + 404) --- SUCCESS");
    }

    // ================================================================
    // BLOCK SEARCH TESTS
    // ================================================================
    @Test
    void blockSearchNumeric_shouldFilterCorrectly() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"accuracy\":0.95}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"accuracy\":0.80}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"accuracy\":0.91}}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
        greenPrint("Numeric block search --- SUCCESS");
    }

    @Test
    void blockSearchText_shouldFilterCorrectly() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"status\":\"completed\"}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"status\":\"running\"}}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "status")
                        .param("op", "eq")
                        .param("value", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        greenPrint("Text block search --- SUCCESS");
    }

    // ================================================================
    // BATCH ISOLATION TESTS
    // ================================================================
    @Test
    void batchIsolation_ingestWithBatch_shouldStoreBatch() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"metrics\":{\"accuracy\":0.95}},\"batch\":\"sweep-A\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "sweep-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        greenPrint("Batch ingestion and search --- SUCCESS");
    }

    @Test
    void batchIsolation_metricsEndpoint_shouldReturnCorrectKeysPerBatch() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"acc\":0.9,\"loss\":0.1}},\"batch\":\"batch-1\"}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"f1\":0.8,\"precision\":0.7}},\"batch\":\"batch-2\"}"));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "batch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("acc", "loss")));
        mockMvc.perform(get("/api/runs/metrics").param("batch", "batch-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("f1", "precision")));
        mockMvc.perform(get("/api/runs/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("acc", "loss", "f1", "precision")));
        greenPrint("Metric discovery per batch --- SUCCESS");
    }

    @Test
    void batchIsolation_searchWithinBatch_shouldReturnOnlyBatchRuns() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"score\":0.95}},\"batch\":\"batch-A\"}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"metrics\":{\"score\":0.85}},\"batch\":\"batch-B\"}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "score")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "batch-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "score")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "batch-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        greenPrint("Batch isolation (cross‑folder search) --- SUCCESS");
    }

    // ================================================================
    // VALIDATION TESTS
    // ================================================================
    @Test
    void blockSearch_invalidOperator_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "invalid")
                        .param("value", "0.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("op")));
        greenPrint("Invalid operator validation --- SUCCESS");
    }
}