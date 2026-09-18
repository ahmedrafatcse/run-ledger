package com.runledger.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Verifies the identity filter contract from Slice 4.
 *
 * <p>Requests must supply {@code X-User-Id}. The filter looks up the user in
 * {@code app_users}, populates {@link AppSecurityContext}, and clears it at
 * the end of each request. Missing, malformed, and unknown identities are
 * rejected with the appropriate status code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class IdentityFilterIntegrationTest {

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

    private static final String ALICE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUP_ID   = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-000000000000";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void seedUsers() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO teams (id, name) VALUES
                    ('11111111-1111-1111-1111-111111111111', 'Team A'),
                    ('22222222-2222-2222-2222-222222222222', 'Team B')
                    ON CONFLICT (id) DO NOTHING
                    """);

            stmt.execute("""
                    INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
                    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111'),
                    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com', 'Sup', 'supervisor', NULL)
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
    }

    @Test
    void missingHeader_returns401() {
        ResponseEntity<String> response = get("/api/runs", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Missing X-User-Id");
    }

    @Test
    void malformedUuid_returns400() {
        ResponseEntity<String> response = get("/api/runs", "not-a-uuid");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("must be a valid UUID");
    }

    @Test
    void unknownUser_returns401() {
        ResponseEntity<String> response = get("/api/runs", UNKNOWN_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Unknown user identity");
    }

    @Test
    void validResearcher_returns200() {
        ResponseEntity<String> response = get("/api/runs", ALICE_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validSupervisor_returns200() {
        ResponseEntity<String> response = get("/api/runs", SUP_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorHealth_skipsFilter() {
        ResponseEntity<String> response = get("/actuator/health", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sequentialRequests_contextDoesNotLeak() {
        // Alice succeeds
        assertThat(get("/api/runs", ALICE_ID).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Unknown ID rejected — this must not leave Alice's identity bound
        assertThat(get("/api/runs", UNKNOWN_ID).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Supervisor succeeds — proves the rejected request didn't poison the thread
        assertThat(get("/api/runs", SUP_ID).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Missing header rejected
        assertThat(get("/api/runs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Alice again — still works
        assertThat(get("/api/runs", ALICE_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path, String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}