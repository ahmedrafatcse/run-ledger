package com.runledger;

import com.jayway.jsonpath.JsonPath;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
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
    private RunRepository runRepository;   // used to clean the database between tests

    @BeforeEach
    void setUp() throws Exception {
        // Clean leftover data from previous tests so that assertions on counts are predictable
        runRepository.deleteAll();

        // Ingest three runs with known values
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"experiment\":\"A\",\"metrics\":{\"accuracy\":0.95,\"loss\":0.10}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"experiment\":\"B\",\"metrics\":{\"accuracy\":0.80,\"loss\":0.25}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"experiment\":\"C\",\"metrics\":{\"accuracy\":0.91,\"loss\":0.15,\"status\":\"completed\"}}}"));
    }

    // ---------- POST /api/runs – ingestion ----------

    @Test
    void shouldIngestRunAndReturn201() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"accuracy\":0.88}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.payload.accuracy").value(0.88))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void shouldRejectMissingPayload() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("payload")));
    }

    // ---------- GET /api/runs/{id} ----------

    @Test
    void shouldReturnRunById() throws Exception {
        // Use the search API to get the ID of a known run
        String response = mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "eq")
                        .param("value", "0.80"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int id = JsonPath.read(response, "$.content[0].id");

        mockMvc.perform(get("/api/runs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void shouldReturn404ForMissingRun() throws Exception {
        mockMvc.perform(get("/api/runs/99999"))
                .andExpect(status().isNotFound());
    }

    // ---------- GET /api/runs – block search ----------

    @Test
    void shouldFilterByMetricGreaterThan() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[?(@.payload.experiment == 'A')]").exists())
                .andExpect(jsonPath("$.content[?(@.payload.experiment == 'C')]").exists());
    }

    @Test
    void shouldFilterByTextEquality() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("metric", "status")
                        .param("op", "eq")
                        .param("value", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].payload.experiment").value("C"));
    }

    @Test
    void shouldReturnEmptyArrayWhenNoMatch() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void shouldRejectInvalidOperator() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "invalid")
                        .param("value", "0.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("op")));
    }
}