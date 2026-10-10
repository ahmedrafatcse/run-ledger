package com.runledger.security;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a browser session authenticated as Alice gets a 404 when
 * requesting a run owned by another team, by ID, directly.
 *
 * <p>This is the HTTP-level counterpart to RlsIsolationTest's service-layer
 * proof. FilterScopingTest covers filter scoping; this covers the response
 * the browser session actually receives. Both are needed: the service-layer
 * test proves RLS filters rows; this one proves the page a user reaches
 * when they ask for a row they can't see.
 *
 * <p>Uses real login (POST /login with CSRF token) so the cookie is genuine
 * and the identity flows through SessionIdentityFilter, not a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RlsHttpIsolationTest {

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

    @LocalServerPort
    private int port;

    @Autowired
    private RunRepository runRepository;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final CookieManager cookieManager = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookieManager)
            .build();

    private Long aliceRunId;
    private Long bobRunId;

    /**
     * Seed teams, users, and one run per team — via the owner connection,
     * so RLS doesn't block the setup.
     */
    @BeforeEach
    void seed() throws Exception {
        cookieManager.getCookieStore().removeAll();

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
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT (id) DO NOTHING
                """);

            aliceRunId = insertRun(conn, TEAM_A, ALICE, "alice-run.json");
            bobRunId   = insertRun(conn, TEAM_B,
                    UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "bob-run.json");
        }
    }

    private Long insertRun(Connection conn, UUID teamId, UUID uploadedBy, String sourceFile)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, canon_version, team_id, uploaded_by)
                VALUES (?::jsonb, ?, 0, 1, true, ?, 'v1', ?::uuid, ?::uuid)
                RETURNING id
                """)) {
            ps.setString(1, "{\"experiment\":\"rls-http-test\"}");
            ps.setString(2, sourceFile);
            ps.setString(3, "placeholder-hash-" + sourceFile);
            ps.setString(4, teamId.toString());
            ps.setString(5, uploadedBy.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String fetchCsrfToken(String html) {
        int nameIdx = html.indexOf("name=\"_csrf\"");
        if (nameIdx == -1) return null;
        int valueIdx = html.indexOf("value=\"", nameIdx);
        if (valueIdx == -1) return null;
        int start = valueIdx + "value=\"".length();
        int end = html.indexOf('"', start);
        return end == -1 ? null : html.substring(start, end);
    }

    /** Logs in as the given user via the real form and stores the session cookie. */
    private void loginAs(String userId) throws Exception {
        HttpResponse<String> loginPage = client.send(
                HttpRequest.newBuilder().uri(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = fetchCsrfToken(loginPage.body());
        assertThat(csrf).as("login form must render a CSRF token").isNotNull();

        String body = "userId=" + userId + "&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        client.send(
                HttpRequest.newBuilder().uri(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithSession(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------
    // The claim: Alice's browser session cannot fetch Bob's run by ID.
    // -----------------------------------------------------------------

    @Test
    void aliceRequestingBobsRunById_returns404() throws Exception {
        loginAs(ALICE.toString());

        HttpResponse<String> response = getWithSession("/runs/" + bobRunId);

        assertThat(response.statusCode())
                .as("RLS filters Bob's row out; the page renders the 404 view")
                .isEqualTo(404);
        // Normalize whitespace before asserting: the template wraps the
        // sentence across two lines, so a contiguous substring check would
        // miss it even though the text is present.
        String bodyNormalized = response.body().replaceAll("\\s+", " ");
        assertThat(bodyNormalized)
                .as("custom 404 template renders, not the Spring default error page")
                .contains("belongs to a team you don't have access to");
    }

    // -----------------------------------------------------------------
    // Sanity check: Alice can still reach her own runs. Proves the 404
    // above is RLS filtering, not a broken detail page.
    // -----------------------------------------------------------------

    @Test
    void aliceRequestingOwnRunById_returns200() throws Exception {
        loginAs(ALICE.toString());

        HttpResponse<String> response = getWithSession("/runs/" + aliceRunId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("alice-run.json");
    }

    // -----------------------------------------------------------------
    // Sanity check on the run list: Bob's run doesn't appear in Alice's
    // list either. Redundant with RlsIsolationTest at the service layer,
    // but proving it at the HTTP layer closes the loop on "the browser
    // session sees the same thing the service layer does."
    // -----------------------------------------------------------------

    @Test
    void aliceRunList_doesNotContainBobsRun() throws Exception {
        loginAs(ALICE.toString());

        HttpResponse<String> response = getWithSession("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("Alice's list must not mention Bob's file")
                .doesNotContain("bob-run.json");
        assertThat(response.body())
                .as("Alice's list must contain her own")
                .contains("alice-run.json");
    }

    @Test
    void aliceRequestingBobsRunHistory_returns404() throws Exception {
        loginAs(ALICE.toString());

        HttpResponse<String> response = getWithSession("/runs/" + bobRunId + "/history");

        assertThat(response.statusCode())
                .as("history page must not expose Bob's versions to Alice")
                .isEqualTo(404);
    }
}