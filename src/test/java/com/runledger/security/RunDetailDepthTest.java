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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /runs/{id}?depth=N truncates the rendered payload to N nesting levels.
 * Scalars always show; containers past the limit render empty. Invalid
 * values fall back to the full payload rather than erroring.
 *
 * <p>Assertions match the HTML-escaped form ({@code &quot;}) because
 * {@code th:text} escapes double quotes when writing the payload into
 * the {@code <pre>} block.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RunDetailDepthTest {

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
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String NESTED_PAYLOAD =
            "{\"top\":{\"middle\":{\"leaf\":42}},\"scalar\":\"hello\",\"arr\":[{\"x\":1}]}";

    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build();

    private Long runId;

    @BeforeEach
    void seed() throws Exception {
        cookies.getCookieStore().removeAll();
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

            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO run (payload, source_file, source_index, version, latest,
                                     payload_hash, canon_version, team_id, uploaded_by, batch)
                    VALUES (?::jsonb, 'nested.json', 0, 1, true,
                            'placeholder-hash-nested.json', 'v1',
                            '11111111-1111-1111-1111-111111111111',
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'depth-batch')
                    RETURNING id
                    """)) {
                ps.setString(1, NESTED_PAYLOAD);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    runId = rs.getLong(1);
                }
            }
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
        client.send(
                HttpRequest.newBuilder().uri(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void depth1_showsTopLevelKeysOnly() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/" + runId + "?depth=1");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("&quot;top&quot;")
                .contains("&quot;scalar&quot;")
                .contains("&quot;arr&quot;");
        assertThat(r.body())
                .as("nested content beyond one level must not render")
                .doesNotContain("&quot;middle&quot;")
                .doesNotContain("&quot;leaf&quot;")
                .doesNotContain("42");
    }

    @Test
    void depth2_showsOneMoreLevel() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/" + runId + "?depth=2");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("&quot;middle&quot;");
        assertThat(r.body())
                .as("depth 2 stops before the leaf")
                .doesNotContain("&quot;leaf&quot;")
                .doesNotContain("42");
    }

    @Test
    void depthAll_showsEverything() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/" + runId + "?depth=all");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("&quot;leaf&quot;")
                .contains("42")
                .contains("hello");
    }

    @Test
    void defaultDepth_rendersWithoutParam() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/" + runId);

        assertThat(r.statusCode()).isEqualTo(200);
        // Default is 3, so the leaf should be visible.
        assertThat(r.body()).contains("&quot;leaf&quot;").contains("42");
    }

    @Test
    void invalidDepth_fallsBackNot500() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/" + runId + "?depth=banana");

        assertThat(r.statusCode())
                .as("bad depth is a viewing hint, not an error")
                .isEqualTo(200);
        // Falls back to full payload.
        assertThat(r.body()).contains("&quot;leaf&quot;").contains("42");
    }
}