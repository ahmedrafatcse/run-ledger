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
 * The /saved page must render for a logged-in user, show only their own
 * saved searches, and reject anonymous requests with a redirect.
 *
 * <p>Owner isolation is proven at the API layer by RlsHttpDataIsolationTest;
 * this test proves the page path (SessionIdentityFilter + SavedPageController)
 * preserves it too. The two are different entry points and both need coverage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class SavedPageAccessTest {

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

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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

            stmt.execute("TRUNCATE TABLE saved_search RESTART IDENTITY");
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

            // One saved search per owner. Distinct names so assertions
            // can't pass by accident on shared substrings.
            stmt.execute("""
                INSERT INTO saved_search (owner_id, name, batch, params_json, created_at) VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice-agg', NULL,
                   '{"mode":"aggregate","agg":"AVG","metric":"accuracy"}'::jsonb, now()),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob-phrase', NULL,
                   '{"mode":"phrase","q":"weighted","fuzzy":true}'::jsonb, now())
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

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------

    @Test
    void researcherSeesOwnSavedSearches() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/saved");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("alice-agg");
        assertThat(r.body()).doesNotContain("bob-phrase");
    }

    @Test
    void bobDoesNotSeeAlicesSavedSearches() throws Exception {
        loginAs(BOB);
        HttpResponse<String> r = get("/saved");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("bob-phrase");
        assertThat(r.body()).doesNotContain("alice-agg");
    }

    @Test
    void anonymousGetsRedirectedToLogin() throws Exception {
        HttpResponse<String> r = get("/saved");

        assertThat(r.statusCode())
                .as("SessionIdentityFilter should reject; 302 to /login")
                .isIn(302, 401);
    }

    @Test
    void saveFromQueryPage_persistsAndAppearsInList() throws Exception {
        loginAs(ALICE);

        // Fetch a fresh CSRF token from the /saved page's delete form.
        HttpResponse<String> savedPage = get("/saved");
        String csrf = fetchCsrf(savedPage.body());
        assertThat(csrf).as("/saved must expose CSRF via delete form").isNotNull();

        String body = "name=my-new-search&mode=phrase&q=weighted&fuzzy=true&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/saved", body);

        assertThat(post.statusCode()).as("POST /saved must 302 (PRG)").isEqualTo(302);

        HttpResponse<String> after = get("/saved");
        assertThat(after.body()).contains("my-new-search");
    }

    @Test
    void deleteRemovesOwnSavedSearch() throws Exception {
        loginAs(ALICE);

        HttpResponse<String> savedPage = get("/saved");
        assertThat(savedPage.body()).contains("alice-agg");
        String csrf = fetchCsrf(savedPage.body());
        assertThat(csrf).isNotNull();

        String body = "name=alice-agg&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/saved/delete", body);
        assertThat(post.statusCode()).isEqualTo(302);

        HttpResponse<String> after = get("/saved");
        assertThat(after.body())
                .as("after delete, the row is gone; the flash message may still echo the name")
                .doesNotContain(">alice-agg<");
    }

    @Test
    void deleteCannotRemoveAnotherOwnersSavedSearch() throws Exception {
        loginAs(BOB);

        HttpResponse<String> savedPage = get("/saved");
        String csrf = fetchCsrf(savedPage.body());
        assertThat(csrf).isNotNull();

        // Bob tries to delete Alice's saved search by name.
        String body = "name=alice-agg&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/saved/delete", body);
        assertThat(post.statusCode()).isEqualTo(302);

        // Alice's row must still exist: log in as her and verify.
        cookies.getCookieStore().removeAll();
        loginAs(ALICE);
        HttpResponse<String> after = get("/saved");
        assertThat(after.body())
                .as("Bob's delete must not touch Alice's row")
                .contains("alice-agg");
    }
}