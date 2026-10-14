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
 * /batches/{teamId}/{batch} renders the metric keys for one team's batch.
 * Authorization: caller's team (researcher) or assigned teams (supervisor).
 * Foreign team -> 404, indistinguishable from unknown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BatchesMetricsPageAccessTest {

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
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL)
                ON CONFLICT (id) DO NOTHING
                """);

            // Sup assigned to Team A only, so Team B is a clean unassigned case.
            stmt.execute("""
                INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111')
                ON CONFLICT DO NOTHING
                """);

            // Alice's mapping has two keys. Team B has one, but Sup can't see it.
            stmt.execute("""
                INSERT INTO batch_schema (team_id, batch, key_mapping) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'alice-batch',
                   '{"accuracy": ["accuracy"], "loss": ["loss"]}'::jsonb),
                  ('22222222-2222-2222-2222-222222222222', 'bob-batch',
                   '{"secret_metric": ["secret_metric"]}'::jsonb)
                """);
        }
    }

    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private String fetchCsrf(String html) {
        int n = html.indexOf("name=\"_csrf\"");
        if (n == -1) return null;
        int v = html.indexOf("value=\"", n);
        if (v == -1) return null;
        int s = v + "value=\"".length();
        int e = html.indexOf('"', s);
        return e == -1 ? null : html.substring(s, e);
    }

    private void loginAs(UUID userId) throws Exception {
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder().uri(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = fetchCsrf(page.body());
        assertThat(csrf).as("login form must render CSRF token").isNotNull();

        String body = "userId=" + userId + "&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder().uri(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("login POST must redirect").isEqualTo(302);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aliceOwnBatch_showsKeys() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/batches/" + TEAM_A + "/alice-batch");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("accuracy").contains("loss");
        assertThat(r.body()).doesNotContain("secret_metric");
    }

    @Test
    void aliceForeignTeam_returns404() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/batches/" + TEAM_B + "/bob-batch");

        assertThat(r.statusCode())
                .as("foreign team indistinguishable from unknown")
                .isEqualTo(404);
    }

    @Test
    void supervisorAssignedTeam_returns200() throws Exception {
        loginAs(SUP);
        HttpResponse<String> r = get("/batches/" + TEAM_A + "/alice-batch");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("accuracy").contains("loss");
    }

    @Test
    void supervisorUnassignedTeam_returns404() throws Exception {
        loginAs(SUP);
        HttpResponse<String> r = get("/batches/" + TEAM_B + "/bob-batch");

        assertThat(r.statusCode())
                .as("Team B is not assigned to Sup; the page must 404")
                .isEqualTo(404);
    }

    @Test
    void authorizedTeamUnknownBatch_rendersEmptyState() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/batches/" + TEAM_A + "/no-such-batch");

        assertThat(r.statusCode())
                .as("authorized team, unknown batch: 200 with empty state, not 404")
                .isEqualTo(200);
        assertThat(r.body()).contains("No metrics discovered");
    }

    @Test
    void aggregateDeepLinkPresentForEachKey() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/batches/" + TEAM_A + "/alice-batch");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .as("each key should link into the aggregate form pre-filled")
                .contains("/query/aggregate")
                .contains("metric=accuracy")
                .contains("metric=loss");
    }
}