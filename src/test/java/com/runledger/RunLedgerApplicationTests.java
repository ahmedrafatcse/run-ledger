package com.runledger;

import com.runledger.config.SecurityConfig;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class RunLedgerApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("runledger")
            .withUsername("runledger")
            .withPassword("runledger")
            .withInitScript("initdb/01-create-app-role.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "runledger_app");
        registry.add("spring.datasource.password", () -> "runledger");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunRepository runRepository;

    /**
     * Cleanup between tests. Runs as the container owner (runledger) because
     * the application role (runledger_app) has no DELETE privilege — which is
     * exactly the security property Slice 3 establishes.
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE run RESTART IDENTITY");
        }
    }

    // ================================================================
    // SINGLE RUN TESTS
    // ================================================================
    @Test
    void singleRunIngestion_shouldReturn201() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"_source\":{\"file\":\"test1\",\"index\":0},\"accuracy\":0.88}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.payload.accuracy").value(0.88))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        System.out.println("Single run ingestion --- SUCCESS");
    }

    @Test
    void missingPayload_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("payload")));
        System.out.println("Missing payload rejection --- SUCCESS");
    }

    @Test
    void singleRunRetrieval_shouldReturn200_and404() throws Exception {
        String response = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"_source\":{\"file\":\"test2\",\"index\":0},\"accuracy\":0.95}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        mockMvc.perform(get("/api/runs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(get("/api/runs/99999"))
                .andExpect(status().isNotFound());
        System.out.println("Run retrieval (200 + 404) --- SUCCESS");
    }

    // ================================================================
    // BLOCK SEARCH TESTS
    // ================================================================
    @Test
    void blockSearchNumeric_shouldFilterCorrectly() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"num1\",\"index\":0},\"metrics\":{\"accuracy\":0.95}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"num2\",\"index\":0},\"metrics\":{\"accuracy\":0.80}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"num3\",\"index\":0},\"metrics\":{\"accuracy\":0.91}}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.accuracy")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
        System.out.println("Numeric block search --- SUCCESS");
    }

    @Test
    void blockSearchText_shouldFilterCorrectly() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"text1\",\"index\":0},\"metrics\":{\"status\":\"completed\"}}}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"text2\",\"index\":0},\"metrics\":{\"status\":\"running\"}}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.status")
                        .param("op", "eq")
                        .param("value", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Text block search --- SUCCESS");
    }

    // ================================================================
    // BATCH ISOLATION TESTS
    // ================================================================
    @Test
    void batchIsolation_ingestWithBatch_shouldStoreBatch() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"_source\":{\"file\":\"batch1\",\"index\":0},\"metrics\":{\"accuracy\":0.95}},\"batch\":\"sweep-A\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "sweep-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Batch ingestion and search --- SUCCESS");
    }

    @Test
    void batchIsolation_metricsEndpoint_shouldReturnCorrectKeysPerBatch() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"m1\",\"index\":0},\"metrics\":{\"acc\":0.9,\"loss\":0.1}},\"batch\":\"batch-1\"}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"m2\",\"index\":0},\"metrics\":{\"f1\":0.8,\"precision\":0.7}},\"batch\":\"batch-2\"}"));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "batch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("acc", "loss")));
        mockMvc.perform(get("/api/runs/metrics").param("batch", "batch-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("f1", "precision")));
        mockMvc.perform(get("/api/runs/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("acc", "loss", "f1", "precision")));
        System.out.println("Metric discovery per batch --- SUCCESS");
    }

    @Test
    void batchIsolation_searchWithinBatch_shouldReturnOnlyBatchRuns() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"iso1\",\"index\":0},\"metrics\":{\"score\":0.95}},\"batch\":\"batch-A\"}"));
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"iso2\",\"index\":0},\"metrics\":{\"score\":0.85}},\"batch\":\"batch-B\"}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.score")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "batch-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.score")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "batch-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        System.out.println("Batch isolation (cross-folder search) --- SUCCESS");
    }

    // ================================================================
    // GENERALISED PATH & BATCH‑KEY MAPPING TESTS
    // ================================================================
    @Test
    void batchMetricDiscovery_returnsShorthandKeysFromMapping() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"sh1\",\"index\":0},\"metrics\":{\"accuracy\":0.95}},\"batch\":\"batch-shorthand\"}"));

        mockMvc.perform(get("/api/runs/metrics").param("batch", "batch-shorthand"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("accuracy")));
        System.out.println("Batch metric discovery (shorthand keys) --- SUCCESS");
    }

    @Test
    void shorthandSearch_resolvesKeyCorrectly() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"sh2\",\"index\":0},\"metrics\":{\"accuracy\":0.95}},\"batch\":\"batch-resolve\"}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "accuracy")
                        .param("op", "gt")
                        .param("value", "0.9")
                        .param("batch", "batch-resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Shorthand search resolves correctly --- SUCCESS");
    }

    @Test
    void dotPathSearch_withoutBatch_works() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"dot1\",\"index\":0},\"metrics\":{\"accuracy\":0.95}}}"));

        mockMvc.perform(get("/api/runs")
                        .param("metric", "metrics.accuracy")
                        .param("op", "gt")
                        .param("value", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Dot‑path search without batch --- SUCCESS");
    }

    // ================================================================
    // FULL‑TEXT PHRASE SEARCH TEST
    // ================================================================
    @Test
    void fullTextPhraseSearch_findsMatchingRun() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"ft1\",\"index\":0},\"experiment\":\"backdoor_defense\",\"notes\":\"applied weighted averaging to merge\"}}"));

        mockMvc.perform(get("/api/runs")
                        .param("q", "weighted averaging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Full‑text phrase search --- SUCCESS");
    }

    // ================================================================
    // FUZZY SEARCH TEST
    // ================================================================
    @Test
    void fuzzySearch_findsApproximateMatch() throws Exception {
        mockMvc.perform(post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":{\"_source\":{\"file\":\"fz1\",\"index\":0},\"experiment\":\"backdoor_defense\",\"notes\":\"applied weighted averaging\"}}"));

        mockMvc.perform(get("/api/runs")
                        .param("q", "weighted avaraging")
                        .param("fuzzy", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
        System.out.println("Fuzzy search --- SUCCESS");
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
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("op")));
        System.out.println("Invalid operator validation --- SUCCESS");
    }

    @Test
    void savedSearch_saveLoadDelete_shouldWork() throws Exception {
        String params = "{\"metrics\":[\"accuracy\"],\"ops\":[\"gt\"],\"values\":[\"0.9\"],\"combine\":\"and\"}";

        // 1. Save (paramsJson is a JSON string, not an object)
        mockMvc.perform(post("/api/saved")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test-save\",\"batch\":\"sample\",\"paramsJson\":\""
                                + params.replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("test-save"));

        // 2. Load – assert on substrings only, since the server may normalize
        //    key ordering and colon spacing when it round-trips the JSON string.
        mockMvc.perform(get("/api/saved/test-save").param("batch", "sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paramsJson").isString())
                .andExpect(jsonPath("$.paramsJson").value(containsString("accuracy")))
                .andExpect(jsonPath("$.paramsJson").value(containsString("gt")))
                .andExpect(jsonPath("$.paramsJson").value(containsString("0.9")));

        // 3. List
        mockMvc.perform(get("/api/saved").param("batch", "sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("test-save"));

        // 4. Delete
        mockMvc.perform(delete("/api/saved/test-save").param("batch", "sample"))
                .andExpect(status().isNoContent());

        // 5. Confirm deleted
        mockMvc.perform(get("/api/saved/test-save").param("batch", "sample"))
                .andExpect(status().isNotFound());
    }
}