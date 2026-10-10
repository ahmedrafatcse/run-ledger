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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crawls every navigation link that appears in the shared header.
 *
 * <p>The test does not hardcode the list of links. It fetches an
 * authenticated page, extracts anchors marked with {@code data-nav="true"}
 * from the rendered HTML, and hits each one. Adding a new link to
 * {@code fragments/layout.html} automatically puts it under this test.
 *
 * <p>Asserts the response per role:
 * <ul>
 *   <li>Researcher → 200</li>
 *   <li>Supervisor → 200</li>
 *   <li>Anonymous  → 302 to /login</li>
 * </ul>
 *
 * <p>This is the guard that would have caught the 6.6 supervisor-500
 * automatically. Any future nav link whose page breaks for a role fails
 * here, before it reaches a demo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class NavCrawlTest {

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
    private static final String SUP   = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private final CookieManager cookieManager = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookieManager)
            .build();

    @BeforeEach
    void seed() throws Exception {
        cookieManager.getCookieStore().removeAll();

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
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL)
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT DO NOTHING
                """);
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

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Extract href values from anchors marked {@code data-nav="true"}.
     * Attribute order in the rendered HTML doesn't matter — the regex
     * scans each anchor for the marker, then extracts the href from the
     * same tag.
     */
    private List<String> extractNavLinks(String html) {
        Pattern anchor = Pattern.compile(
                "<a\\s+[^>]*data-nav=\"true\"[^>]*>",
                Pattern.CASE_INSENSITIVE);
        Pattern href = Pattern.compile("href=\"([^\"]+)\"");

        List<String> links = new ArrayList<>();
        Matcher m = anchor.matcher(html);
        while (m.find()) {
            Matcher hm = href.matcher(m.group());
            if (hm.find()) {
                links.add(hm.group(1));
            }
        }
        return links;
    }

    @Test
    void everyNavLink_returns200AsResearcher() throws Exception {
        loginAs(ALICE);

        HttpResponse<String> home = get("/");
        assertThat(home.statusCode()).isEqualTo(200);

        List<String> links = extractNavLinks(home.body());
        assertThat(links)
                .as("home page should expose at least one nav link")
                .isNotEmpty();

        for (String link : links) {
            HttpResponse<String> r = get(link);
            assertThat(r.statusCode())
                    .as("nav link %s must return 200 for researcher", link)
                    .isEqualTo(200);
        }
    }

    @Test
    void everyNavLink_returns200AsSupervisor() throws Exception {
        loginAs(SUP);

        HttpResponse<String> home = get("/");
        assertThat(home.statusCode()).isEqualTo(200);

        List<String> links = extractNavLinks(home.body());
        assertThat(links).isNotEmpty();

        for (String link : links) {
            HttpResponse<String> r = get(link);
            assertThat(r.statusCode())
                    .as("nav link %s must return 200 for supervisor", link)
                    .isEqualTo(200);
        }
    }

    @Test
    void everyNavLink_redirectsToLoginWhenAnonymous() throws Exception {
        // No login. Hit /login first so we can extract links without a
        // session — but /login itself has no nav. Instead, fetch the
        // authenticated home page in a scratch state, extract the links,
        // then verify they redirect when anonymous.
        loginAs(ALICE);
        HttpResponse<String> home = get("/");
        List<String> links = extractNavLinks(home.body());
        assertThat(links).isNotEmpty();

        // Wipe the session so the next requests are anonymous.
        cookieManager.getCookieStore().removeAll();

        for (String link : links) {
            HttpResponse<String> r = get(link);
            assertThat(r.statusCode())
                    .as("nav link %s must redirect anonymous visitors to /login", link)
                    .isEqualTo(302);
            assertThat(r.headers().firstValue("Location").orElse(""))
                    .endsWith("/login");
        }
    }
}