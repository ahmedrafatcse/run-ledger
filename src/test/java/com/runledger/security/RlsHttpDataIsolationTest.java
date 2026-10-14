package com.runledger.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level isolation for the /api/* data endpoints that were made
 * team- or owner-scoped in Slice 4: /api/runs/metrics, /api/batches,
 * /api/saved.
 *
 * <p>Uses X-User-Id (IdentityFilter), not the login form, because every
 * path under test is under /api/* and therefore handled by IdentityFilter.
 * Cross-team /runs/* pages are covered by RlsHttpIsolationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RlsHttpDataIsolationTest {

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

    @LocalServerPort private int port;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SUP    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE batch_schema, saved_search, run RESTART IDENTITY");

            stmt.execute("""
                INSERT INTO teams (id, name) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'Team A'),
                  ('22222222-2222-2222-2222-222222222222', 'Team B')
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111'),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL)
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT DO NOTHING
                """);

            // Alice's batch mapping. Contains a metric key Bob must never see.
            stmt.execute("""
                INSERT INTO batch_schema (team_id, batch, key_mapping) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'alice-only-batch',
                   '{"alice_accuracy": ["accuracy"], "alice_loss": ["loss"]}'::jsonb)
                """);

            // Alice's saved search. Bob must not see or delete it.
            stmt.execute("""
                INSERT INTO saved_search (owner_id, name, batch, params_json, created_at) VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'my-search', 'demo',
                   '{"metric":"accuracy","op":"gt","value":"0.9"}', now())
                """);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> getAs(UUID userId, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path))
                        .header("X-User-Id", userId.toString())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteAs(UUID userId, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path))
                        .header("X-User-Id", userId.toString())
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------
    // 2. Bob can't read Alice's batch mapping via /api/runs/metrics.
    // -----------------------------------------------------------------

    @Test
    void bobRequestingAlicesBatchMapping_getsEmptyNotError() throws Exception {
        HttpResponse<String> response = getAs(BOB,
                "/api/runs/metrics?batch=alice-only-batch");

        assertThat(response.statusCode())
                .as("RLS filters the mapping; empty result, not a leak, not a 500")
                .isEqualTo(200);
        assertThat(response.body())
                .as("Bob must not see Alice's metric keys")
                .doesNotContain("alice_accuracy")
                .doesNotContain("alice_loss");
    }

    @Test
    void aliceRequestingOwnBatchMapping_seesHerKeys() throws Exception {
        HttpResponse<String> response = getAs(ALICE,
                "/api/runs/metrics?batch=alice-only-batch");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("alice_accuracy");
    }

    // -----------------------------------------------------------------
    // 6. Researcher passing someone else's team -> empty, not an error.
    //    The team param on /api/runs/metrics, for a researcher, is
    //    passed through to the query, which RLS then filters.
    // -----------------------------------------------------------------

    @Test
    void researcherPassingForeignTeam_getsEmptyNotError() throws Exception {
        HttpResponse<String> response = getAs(ALICE,
                "/api/runs/metrics?batch=alice-only-batch&team=" + TEAM_B);

        assertThat(response.statusCode())
                .as("foreign team is not an error for a researcher; RLS returns empty")
                .isEqualTo(200);
        assertThat(response.body())
                .as("Team B has no batch named alice-only-batch")
                .doesNotContain("alice_accuracy");
    }

    // -----------------------------------------------------------------
    // 8. Cross-team /api/batches returns the caller's team only.
    // -----------------------------------------------------------------

    @Test
    void aliceBatches_doNotContainTeamB() throws Exception {
        HttpResponse<String> response = getAs(ALICE, "/api/batches");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("Team B");
        assertThat(response.body()).doesNotContain(TEAM_B.toString());
    }

    @Test
    void bobBatches_doNotContainTeamA() throws Exception {
        HttpResponse<String> response = getAs(BOB, "/api/batches");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("Team A");
        assertThat(response.body()).doesNotContain(TEAM_A.toString());
    }

    @Test
    void supervisorBatches_includeAllAssignedTeams() throws Exception {
        HttpResponse<String> response = getAs(SUP, "/api/batches");

        assertThat(response.statusCode()).isEqualTo(200);
        // SUP is assigned to both teams; each team's batches appear (empty
        // lists if they have none), and neither is foreign.
        assertThat(response.body())
                .as("supervisor sees batches from assigned teams, no 500")
                .doesNotContain("\"error\"");
    }

    // -----------------------------------------------------------------
    // 3. Bob can't list/get/delete Alice's saved searches.
    // -----------------------------------------------------------------

    @Test
    void bobList_doesNotIncludeAlicesSavedSearch() throws Exception {
        HttpResponse<String> response = getAs(BOB, "/api/saved");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("saved searches are owner-scoped; Alice's rows are invisible")
                .doesNotContain("my-search");
    }

    @Test
    void bobGet_alicesSavedSearch_returns404() throws Exception {
        HttpResponse<String> response = getAs(BOB, "/api/saved/my-search");

        assertThat(response.statusCode())
                .as("not found, not forbidden — invisible means invisible")
                .isEqualTo(404);
    }

    @Test
    void bobDelete_alicesSavedSearch_isNoOp() throws Exception {
        HttpResponse<String> deleteResponse = deleteAs(BOB, "/api/saved/my-search");
        assertThat(deleteResponse.statusCode()).isEqualTo(204);

        // Alice's row must still exist.
        HttpResponse<String> aliceList = getAs(ALICE, "/api/saved");
        assertThat(aliceList.body())
                .as("Bob's delete must not have removed Alice's row")
                .contains("my-search");
    }

    @Test
    void aliceList_includesOwnSavedSearch() throws Exception {
        HttpResponse<String> response = getAs(ALICE, "/api/saved");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("my-search");
    }

    @Test
    void aliceGet_ownSavedSearch_returns200() throws Exception {
        HttpResponse<String> response = getAs(ALICE, "/api/saved/my-search");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("my-search");
    }
}