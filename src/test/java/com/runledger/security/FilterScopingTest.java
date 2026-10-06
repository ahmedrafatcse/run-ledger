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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the two identity filters are scoped to disjoint paths and
 * that neither interferes with the other's surface.
 *
 * <p>The critical test is {@link #apiWithoutHeader_returns401Not302()}. If
 * SessionIdentityFilter were registered against {@code /*} — the default
 * outcome of adding a second {@code @Component} filter — that request would
 * return a 302 redirect to {@code /login}, and every CLI call in the
 * existing test suite would break for the same reason. The assertion is
 * directly against that failure mode.
 *
 * <p>Uses {@code java.net.http.HttpClient} with redirects disabled so the
 * 302 is observable rather than followed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class FilterScopingTest {

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

    private static final String ALICE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Seed reference data.
     *
     * <p>Runs before every test rather than once per class: {@code @BeforeAll}
     * executes before the Spring context is up, which means before Flyway has
     * migrated. The seed inserts into {@code teams} and {@code app_users},
     * which don't exist at that point. {@code @BeforeEach} runs after context
     * startup, when the schema is present.
     *
     * <p>The inserts use {@code ON CONFLICT DO NOTHING}, so repeat executions
     * are no-ops after the first. The idempotency means the extra invocations
     * cost nothing.
     */
    @BeforeEach
    void seedUser() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {

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
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String userIdHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri(path)).GET();
        if (userIdHeader != null) {
            builder.header("X-User-Id", userIdHeader);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------
    // Case 1 — API path with header. IdentityFilter handles it.
    // -----------------------------------------------------------------

    @Test
    void apiWithHeader_reachesController() throws Exception {
        HttpResponse<String> r = get("/api/runs", ALICE);

        assertThat(r.statusCode())
                .as("IdentityFilter populates context from header; request reaches controller")
                .isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // Case 2 — API path with no identity. Must be 401, not 302.
    //
    // This is the case that catches the collision. If SessionIdentityFilter
    // ran on /api/*, it would find no session and redirect to /login.
    // Getting 401 proves IdentityFilter ran (or the request reached the
    // controller which rejected it), and that SessionIdentityFilter did not.
    // -----------------------------------------------------------------

    @Test
    void apiWithoutHeader_returns401Not302() throws Exception {
        HttpResponse<String> r = get("/api/runs", null);

        assertThat(r.statusCode())
                .as("SessionIdentityFilter must not run on /api/* — 302 here would break every CLI call")
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // Case 3 — page path with no session. SessionIdentityFilter redirects.
    // -----------------------------------------------------------------

    @Test
    void pageWithoutSession_redirectsToLogin() throws Exception {
        HttpResponse<String> r = get("/", null);

        assertThat(r.statusCode())
                .as("SessionIdentityFilter redirects unauthenticated browser requests")
                .isEqualTo(302);
        assertThat(r.headers().firstValue("Location").orElse(""))
                .endsWith("/login");
    }

    // -----------------------------------------------------------------
    // Case 4 — page path with no session, POST /api/runs. Sanity check
    // that IdentityFilter is what's producing the 401, not the controller.
    // Covered by case 2. Included here as an explicit assertion that the
    // response is not a redirect, since "not a redirect" is the whole point.
    // -----------------------------------------------------------------

    @Test
    void apiWithoutHeader_isNotARedirect() throws Exception {
        HttpResponse<String> r = get("/api/runs", null);

        assertThat(r.statusCode())
                .as("must not be a redirect")
                .isNotEqualTo(302)
                .isNotEqualTo(301)
                .isNotEqualTo(303);
        assertThat(r.headers().firstValue("Location"))
                .as("no Location header means no redirect")
                .isEmpty();
    }

    // -----------------------------------------------------------------
    // Case 5 — page path with no session, but a header is present. The
    // header must be ignored on page routes: SessionIdentityFilter is the
    // only filter running, and it doesn't read headers.
    // -----------------------------------------------------------------

    @Test
    void pageWithHeaderButNoSession_stillRedirectsToLogin() throws Exception {
        HttpResponse<String> r = get("/", ALICE);

        assertThat(r.statusCode())
                .as("IdentityFilter does not run on page paths; session is the only identity source here")
                .isEqualTo(302);
        assertThat(r.headers().firstValue("Location").orElse(""))
                .endsWith("/login");
    }
}