package com.runledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runledger.security.AppSecurityContext;
import com.runledger.security.SecuredTransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ingest merges a run's leaf paths into the (teamId, batch)
 * mapping rather than rebuilding or overwriting.
 *
 * <p>Every service call is wrapped in {@link SecuredTransactionTemplate}
 * so {@code SET LOCAL ROLE runledger_researcher} and
 * {@code set_config('app.current_user_id', ...)} run in the same transaction
 * as the merge and read. Without the wrapper, the INSERT is rejected by
 * FORCE ROW LEVEL SECURITY and the SELECT returns empty.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class BatchSchemaMergeTest {

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

    @Autowired private BatchSchemaService batchSchemaService;
    @Autowired private SecuredTransactionTemplate secured;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE batch_schema, run RESTART IDENTITY");

            stmt.execute("""
                INSERT INTO teams (id, name) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'Team A'),
                  ('22222222-2222-2222-2222-222222222222', 'Team B')
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111'),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT (id) DO NOTHING
                """);
        }
    }

    @AfterEach
    void clear() {
        AppSecurityContext.clear();
    }

    private void bindAlice() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
    }

    private void bindBob() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(BOB, "researcher", TEAM_B));
    }

    private void merge(UUID teamId, String batch, String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            secured.execute(() -> {
                batchSchemaService.mergeIntoMapping(teamId, batch, node);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, List<String>> read(UUID teamId, String batch) {
        return secured.execute(() -> batchSchemaService.getOrCreateMapping(teamId, batch));
    }

    @Test
    void merge_intoEmptyBatch_createsMapping() {
        bindAlice();
        merge(TEAM_A, "test-batch", "{\"accuracy\":0.9,\"loss\":0.1}");

        Map<String, List<String>> mapping = read(TEAM_A, "test-batch");

        assertThat(mapping).containsKeys("accuracy", "loss");
    }

    @Test
    void merge_addsNewPathsToExistingMapping() {
        bindAlice();
        merge(TEAM_A, "test-batch", "{\"accuracy\":0.9}");
        merge(TEAM_A, "test-batch",
                "{\"accuracy\":0.9,\"loss\":0.1,\"config\":{\"lr\":0.001}}");

        Map<String, List<String>> mapping = read(TEAM_A, "test-batch");

        assertThat(mapping).containsKeys("accuracy", "loss", "lr");
    }

    /** Item 1 — the same batch name in two teams is two distinct rows. */
    @Test
    void sameBatchName_inTwoTeams_isTwoDistinctMappings() {
        bindAlice();
        merge(TEAM_A, "shared-name", "{\"alice_metric\":1}");

        bindBob();
        merge(TEAM_B, "shared-name", "{\"bob_metric\":2}");

        bindAlice();
        Map<String, List<String>> teamA = read(TEAM_A, "shared-name");
        bindBob();
        Map<String, List<String>> teamB = read(TEAM_B, "shared-name");

        assertThat(teamA).containsKey("alice_metric").doesNotContainKey("bob_metric");
        assertThat(teamB).containsKey("bob_metric").doesNotContainKey("alice_metric");
    }

    /** Item 7 — concurrent merges into one batch keep both runs' paths. */
    @Test
    void concurrentMerges_intoOneBatch_bothSurvive() throws Exception {
        Runnable m1 = () -> {
            AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
            merge(TEAM_A, "race-batch", "{\"alpha\":1}");
            AppSecurityContext.clear();
        };
        Runnable m2 = () -> {
            AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
            merge(TEAM_A, "race-batch", "{\"beta\":2}");
            AppSecurityContext.clear();
        };

        Thread t1 = new Thread(m1);
        Thread t2 = new Thread(m2);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        bindAlice();
        Map<String, List<String>> mapping = read(TEAM_A, "race-batch");

        assertThat(mapping).containsKeys("alpha", "beta");
    }

    /** Team B reading Team A's batch name must not see Team A's paths. */
    @Test
    void teamBCannotReadTeamAMapping() {
        bindAlice();
        merge(TEAM_A, "alice-only", "{\"secret\":1}");

        bindBob();
        Map<String, List<String>> mapping = read(TEAM_B, "alice-only");

        assertThat(mapping).doesNotContainKey("secret");
    }
}