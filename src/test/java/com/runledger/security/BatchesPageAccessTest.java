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

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The /batches page must render for both roles, show only what the caller
 * can see, and never 500. NavCrawlTest already hits the link anonymously;
 * this test covers the positive paths.
 *
 * <p>Assertions target the batch <em>name</em>, not the team name: the team
 * name appears in the identity header unconditionally, so a
 * {@code contains("Team A")} check could pass for the wrong reason. The
 * batch name only renders if {@code listBatches()} actually returned a row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BatchesPageAccessTest {

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

    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build();

    @BeforeEach
    void seed() throws Exception {
        cookies.getCookieStore().removeAll();
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT (id) DO NOTHING
                """);
            stmt.execute("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, canon_version, team_id, uploaded_by, batch) VALUES
                  ('{"x":1}'::jsonb, 'a.json', 0, 1, true, 'h_a', 'v1',
                   '11111111-1111-1111-1111-111111111111',
                   'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice-batch'),
                  ('{"x":1}'::jsonb, 'b.json', 0, 1, true, 'h_b', 'v1',
                   '22222222-2222-2222-2222-222222222222',
                   'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob-batch')
                """);
        }
    }

    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private String fetchCsrf(String html) {
        int nameIdx = html.indexOf("name=\"_csrf\"");
        if (nameIdx == -1) return null;
        int valueIdx = html.indexOf("value=\"", nameIdx);
        if (valueIdx == -1) return null;
        int start = valueIdx + "value=\"".length();
        int end = html.indexOf('"', start);
        return end == -1 ? null : html.substring(start, end);
    }

    private void loginAs(UUID userId) throws Exception {
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder().uri(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = fetchCsrf(page.body());
        assertThat(csrf).as("login form must render a CSRF token").isNotNull();

        String body = "userId=" + userId + "&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> loginResponse = client.send(
                HttpRequest.newBuilder().uri(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode())
                .as("login POST must succeed (302 to / on success, 403 if CSRF or user invalid)")
                .isEqualTo(302);
    }

    @Test
    void researcherSeesOwnTeamBatch() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder().uri(uri("/batches")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("alice-batch");
        assertThat(r.body()).doesNotContain("bob-batch");
    }

    @Test
    void bobDoesNotSeeTeamA() throws Exception {
        loginAs(BOB);
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder().uri(uri("/batches")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("bob-batch");
        assertThat(r.body()).doesNotContain("alice-batch");
    }

    @Test
    void anonymousGetsRedirectToLogin() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder().uri(uri("/batches"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode())
                .as("SessionIdentityFilter should reject; typical response is 302 or 401")
                .isIn(302, 401);
    }
}