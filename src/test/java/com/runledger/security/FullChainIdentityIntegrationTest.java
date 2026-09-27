package com.runledger.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end identity + transaction integration test.
 *
 * <p>Unlike the other security tests, this one exercises the full chain:
 * the real IdentityFilter runs, populates AppSecurityContext, the request
 * reaches the controller, calls a service method wrapped by
 * SecuredTransactionTemplate, which issues SET LOCAL ROLE and set_config
 * inside the transaction, all against a real Postgres container.
 *
 * <p>HikariCP is configured with a single-connection pool so sequential
 * requests deterministically reuse the same physical connection. This makes
 * the pool-safety property directly testable: if the wrapper leaked session
 * state, or if the filter failed to populate identity, one of these requests
 * would fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class FullChainIdentityIntegrationTest {

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

        // Single-connection pool: sequential requests reuse the same physical
        // connection, so any leaked session state would manifest across
        // requests. This is what makes the pool-safety property testable.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Autowired
    private TestRestTemplate rest;

    private static final String ALICE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String BOB   = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUP   = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE run RESTART IDENTITY");

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
        }
    }

    // ---------------------------------------------------------------
    // Each identity type can reach a wrapped service
    // ---------------------------------------------------------------

    @Test
    void ingestAsResearcher_succeeds() {
        ResponseEntity<String> response = post("/api/runs", ALICE,
                "{\"payload\":{\"_source\":{\"file\":\"fullchain1\",\"index\":0},\"accuracy\":0.95}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void searchAsResearcher_succeeds() {
        post("/api/runs", ALICE,
                "{\"payload\":{\"_source\":{\"file\":\"fullchain2\",\"index\":0},\"accuracy\":0.95}}");

        ResponseEntity<String> response = get(
                "/api/runs?metric=accuracy&op=gt&value=0.9", ALICE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void searchAsSupervisor_succeeds() {
        post("/api/runs", ALICE,
                "{\"payload\":{\"_source\":{\"file\":\"fullchain3\",\"index\":0},\"accuracy\":0.95}}");

        ResponseEntity<String> response = get(
                "/api/runs?metric=accuracy&op=gt&value=0.9", SUP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aggregateAsSupervisor_succeeds() {
        post("/api/runs", ALICE,
                "{\"payload\":{\"_source\":{\"file\":\"fullchain4\",\"index\":0},\"accuracy\":0.95}}");

        ResponseEntity<String> response = get(
                "/api/runs/aggregate?agg=AVG&metric=accuracy", SUP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------
    // Pool-safety: sequential requests with different identities
    // ---------------------------------------------------------------

    @Test
    void sequentialRequestsWithDifferentIdentities_allSucceed() {
        // All four requests go through the same single-connection pool.
        // If the wrapper failed to set identity per request, or if SET LOCAL
        // leaked state, at least one request would fail with an unexpected
        // status code.

        ResponseEntity<String> aliceIngest = post("/api/runs", ALICE,
                "{\"payload\":{\"_source\":{\"file\":\"seq1\",\"index\":0},\"accuracy\":0.95}}");
        assertThat(aliceIngest.getStatusCode())
                .as("Alice's ingest should succeed")
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> bobIngest = post("/api/runs", BOB,
                "{\"payload\":{\"_source\":{\"file\":\"seq2\",\"index\":0},\"accuracy\":0.80}}");
        assertThat(bobIngest.getStatusCode())
                .as("Bob's ingest should succeed on the reused connection")
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> supSearch = get("/api/runs?metric=accuracy&op=gt&value=0.9", SUP);
        assertThat(supSearch.getStatusCode())
                .as("Supervisor's search should succeed after a researcher's request")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> aliceSearch = get("/api/runs?metric=accuracy&op=gt&value=0.9", ALICE);
        assertThat(aliceSearch.getStatusCode())
                .as("Alice's search should succeed after the supervisor's request")
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private ResponseEntity<String> get(String path, String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}