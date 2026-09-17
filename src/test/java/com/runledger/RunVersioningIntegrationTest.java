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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
@ActiveProfiles("test")
class RunVersioningIntegrationTest {

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

    private static final String SOURCE_JSON = """
        {
          "experiment": "version_test",
          "accuracy": 0.95,
          "_source": {"file": "run.json", "index": 0}
        }
        """;

    private static final String MODIFIED_JSON = """
        {
          "experiment": "version_test_modified",
          "accuracy": 0.96,
          "_source": {"file": "run.json", "index": 0}
        }
        """;

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

    @Test
    void reScanUnchanged_shouldNotCreateNewVersion() throws Exception {
        // 1. Ingest original
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated());

        // 2. Re-scan same content
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated());   // idempotent, but controller always responds 201

        // 3. Verify only one row, latest=true, version=1
        long count = runRepository.count();
        assert count == 1 : "Expected exactly 1 run, got " + count;

        runRepository.findAll().forEach(r -> {
            assert r.isLatest() : "Single run should be latest";
            assert r.getVersion() == 1 : "Version should be 1";
        });
    }

    @Test
    void reScanChanged_shouldCreateNewVersionAndMarkOldAsNotLatest() throws Exception {
        // 1. Ingest original
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated());

        // 2. Re-scan modified
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + MODIFIED_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated()); // new version

        // 3. Verify two rows: v1 latest=false, v2 latest=true
        var runs = runRepository.findAll();
        assert runs.size() == 2 : "Expected 2 runs, got " + runs.size();

        var v1 = runs.stream().filter(r -> r.getVersion() == 1).findFirst().orElseThrow();
        var v2 = runs.stream().filter(r -> r.getVersion() == 2).findFirst().orElseThrow();

        assert !v1.isLatest() : "v1 should not be latest";
        assert v2.isLatest() : "v2 should be latest";
    }

    @Test
    void concurrentScans_shouldNotCreateDuplicateLatest() throws Exception {
        // Ingest first to establish identity
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"concurrent\"}"))
                .andExpect(status().isCreated());

        // Launch two concurrent scans with slightly different payloads
        String v2 = """
            {"experiment":"version_test_v2","accuracy":0.97,"_source":{"file":"run.json","index":0}}
            """;
        String v3 = """
            {"experiment":"version_test_v3","accuracy":0.98,"_source":{"file":"run.json","index":0}}
            """;

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                mockMvc.perform(post("/api/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payload\":" + v2 + ",\"batch\":\"concurrent\"}"))
                        .andExpect(status().isCreated());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            try {
                mockMvc.perform(post("/api/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payload\":" + v3 + ",\"batch\":\"concurrent\"}"))
                        .andExpect(status().isCreated());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for both
        try {
            CompletableFuture.allOf(f1, f2).get();
        } catch (ExecutionException e) {
            // One may fail due to unique constraint – that's the expected race condition
        }

        // Verify: no duplicate latest rows
        long latestCount = runRepository.findAll().stream().filter(r -> r.isLatest()).count();
        assert latestCount == 1 : "Expected exactly 1 latest version, got " + latestCount;

        // Also verify total versions ≤3 (original + at most 2 new ones, one may have collided)
        long totalVersions = runRepository.count();
        assert totalVersions >= 2 && totalVersions <= 3 : "Expected 2-3 total versions, got " + totalVersions;
    }

    @Test
    void getVersions_shouldReturnOrderedVersions() throws Exception {
        // 1. Ingest original
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated());

        // 2. Ingest modified (new version)
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + MODIFIED_JSON + ",\"batch\":\"version-batch\"}"))
                .andExpect(status().isCreated());

        // 3. Call /versions endpoint
        mockMvc.perform(get("/api/runs/versions")
                        .param("batch", "version-batch")
                        .param("sourceFile", "run.json")
                        .param("sourceIndex", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].payload.experiment").value("version_test"))
                .andExpect(jsonPath("$[1].payload.experiment").value("version_test_modified"));
    }
}