package com.runledger.service;

import com.runledger.security.AppSecurityContext;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the aggregate SQL against real Postgres. Unit tests can only
 * prove the SQL text is built; this proves it parses and returns the
 * right numbers. The Instant/Timestamp bug from Slice 5.1 was exactly
 * this class of gap: the query ran, the result was read, but no test
 * had ever iterated a non-empty result set.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RunQueryServiceAggregateIntegrationTest {

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

    @Autowired private RunQueryService runQueryService;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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

            // Two runs, each with a results[] array. Values chosen so
            // the expected average is a clean, non-round number.
            insertRun(conn, "agg-batch", "demo",  "run-1.json",
                    "[{\"accuracy\":0.90},{\"accuracy\":0.80}]");
            insertRun(conn, "agg-batch", "demo",  "run-2.json",
                    "[{\"accuracy\":0.70},{\"accuracy\":0.60}]");
            insertRun(conn, "agg-batch", "other", "run-3.json",
                    "[{\"accuracy\":0.50}]");
        }

        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                ALICE, "researcher", TEAM_A));
    }

    private void insertRun(Connection conn, String batch, String experiment,
                           String sourceFile, String resultsJson) throws Exception {
        String payload = "{\"experiment\":\"" + experiment + "\",\"results\":" + resultsJson + "}";
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, canon_version, team_id, uploaded_by, batch)
                VALUES (?::jsonb, ?, 0, 1, true,
                        'placeholder-hash-agg.json', 'v1',
                        '11111111-1111-1111-1111-111111111111',
                        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', ?)
                """)) {
            ps.setString(1, payload);
            ps.setString(2, sourceFile);
            ps.setString(3, batch);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void clear() {
        AppSecurityContext.clear();
    }

    @Test
    void aggregate_arrayPath_returnsRealAverage() {
        // (0.90 + 0.80 + 0.70 + 0.60 + 0.50) / 5 = 0.70
        List<Map<String, Object>> results = runQueryService.aggregate(
                "AVG", "results[].accuracy", null, "agg-batch", null);

        assertThat(results).hasSize(1);
        assertThat((Double) results.get(0).get("result"))
                .isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aggregate_arrayPath_groupBy_returnsPerGroupAverages() {
        // demo: (0.90+0.80+0.70+0.60)/4 = 0.75
        // other: 0.50
        List<Map<String, Object>> results = runQueryService.aggregate(
                "AVG", "results[].accuracy", "experiment", "agg-batch", null);

        assertThat(results).hasSize(2);
        // Sorted by result DESC: demo (0.75) then other (0.50)
        assertThat(results.get(0).get("group")).isEqualTo("demo");
        assertThat((Double) results.get(0).get("result"))
                .isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(results.get(1).get("group")).isEqualTo("other");
        assertThat((Double) results.get(1).get("result"))
                .isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aggregate_scalarPath_stillWorks() {
        List<Map<String, Object>> results = runQueryService.aggregate(
                "COUNT", null, null, "agg-batch", null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("result")).isEqualTo(3.0);
    }

    @Test
    void aggregate_arrayPath_withTeam_scopesByTeam() {
        // Same team, should return the average.
        List<Map<String, Object>> results = runQueryService.aggregate(
                "AVG", "results[].accuracy", null, "agg-batch", TEAM_A);

        assertThat(results).hasSize(1);
        assertThat((Double) results.get(0).get("result"))
                .isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aggregate_arrayPath_foreignTeam_returnsNull() {
        UUID otherTeam = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<Map<String, Object>> results = runQueryService.aggregate(
                "AVG", "results[].accuracy", null, "agg-batch", otherTeam);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("result"))
                .as("AVG over zero rows is NULL, not an error")
                .isNull();
    }
}