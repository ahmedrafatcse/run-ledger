package com.runledger;

import com.runledger.repository.RunRepository;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.SecuredTransactionTemplate;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private SecuredTransactionTemplate secured;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
     * Setup for each test:
     *
     * <p>(1) Truncate + seed teams/users via direct owner connection.
     * Ingestion now sets team_id/uploaded_by from identity, so the FK
     * constraints on run require those referenced rows to exist.
     *
     * <p>(2) Bind Alice (researcher, Team A) to AppSecurityContext so the
     * SecuredTransactionTemplate can resolve the role when the ingestion
     * service runs. The filter is bypassed here (addFilters = false), so
     * without this binding the service would throw on require().
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE run RESTART IDENTITY");

            stmt.execute("""
                INSERT INTO teams (id, name) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'Team A')
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111')
                ON CONFLICT (id) DO NOTHING
                """);
        }

        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
    }

    @AfterEach
    void clearIdentity() {
        AppSecurityContext.clear();
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

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

        // 3. Verify only one row, latest=true, version=1.
        // Direct repository access goes through SecuredTransactionTemplate
        // so the query runs as runledger_researcher with identity bound.
        var runs = secured.execute(() -> runRepository.findAll());
        assertThat(runs.size()).as("Expected exactly 1 run, got " + runs.size()).isEqualTo(1);
        assertThat(runs.get(0).isLatest()).isTrue();
        assertThat(runs.get(0).getVersion()).isEqualTo(1);
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
                .andExpect(status().isCreated());

        // 3. Verify two rows: v1 latest=false, v2 latest=true
        var runs = secured.execute(() -> runRepository.findAll());
        assertThat(runs.size()).as("Expected 2 runs, got " + runs.size()).isEqualTo(2);

        var v1 = runs.stream().filter(r -> r.getVersion() == 1).findFirst().orElseThrow();
        var v2 = runs.stream().filter(r -> r.getVersion() == 2).findFirst().orElseThrow();

        assertThat(v1.isLatest()).as("v1 should not be latest").isFalse();
        assertThat(v2.isLatest()).as("v2 should be latest").isTrue();
    }

    @Test
    void concurrentScans_shouldNotCreateDuplicateLatest() throws Exception {
        // Ingest first to establish identity
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":" + SOURCE_JSON + ",\"batch\":\"concurrent\"}"))
                .andExpect(status().isCreated());

        String v2 = """
            {"experiment":"version_test_v2","accuracy":0.97,"_source":{"file":"run.json","index":0}}
            """;
        String v3 = """
            {"experiment":"version_test_v3","accuracy":0.98,"_source":{"file":"run.json","index":0}}
            """;

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
            try {
                mockMvc.perform(post("/api/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payload\":" + v2 + ",\"batch\":\"concurrent\"}"))
                        .andExpect(status().isCreated());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                AppSecurityContext.clear();
            }
        });

        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
            try {
                mockMvc.perform(post("/api/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payload\":" + v3 + ",\"batch\":\"concurrent\"}"))
                        .andExpect(status().isCreated());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                AppSecurityContext.clear();
            }
        });

        try {
            CompletableFuture.allOf(f1, f2).get();
        } catch (ExecutionException e) {
            // One may fail due to unique constraint — expected race condition
        }

        // Rebind identity on the test thread for the assertions (the async
        // tasks cleared theirs in their finally blocks, but this thread's
        // context is still set from @BeforeEach; the explicit set below is
        // defensive and makes the intent clear).
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        var runs = secured.execute(() -> runRepository.findAll());

        long latestCount = runs.stream().filter(r -> r.isLatest()).count();
        assertThat(latestCount)
                .as("Expected exactly 1 latest version, got " + latestCount)
                .isEqualTo(1);

        long totalVersions = runs.size();
        assertThat(totalVersions)
                .as("Expected 2-3 total versions, got " + totalVersions)
                .isBetween(2L, 3L);
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