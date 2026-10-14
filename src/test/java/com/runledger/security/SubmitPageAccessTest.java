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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /submit renders only for researchers. POST /submit ingests a
 * payload, rejects payloads that lack _source.file, and rejects
 * malformed JSON — all with an actionable message rather than a 500.
 *
 * <p>Supervisors are redirected: RunIngestionService rejects them at the
 * service layer, so rendering a form that always fails would be a UX
 * defect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class SubmitPageAccessTest {

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
    private static final UUID SUP   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

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
                  ('11111111-1111-1111-1111-111111111111', 'Team A')
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
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111')
                ON CONFLICT DO NOTHING
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

    private String csrfFrom(String html) {
        String csrf = fetchCsrf(html);
        assertThat(csrf).as("page must expose CSRF token").isNotNull();
        return csrf;
    }

    private static String form(String... kvPairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (sb.length() > 0) sb.append("&");
            sb.append(kvPairs[i]).append("=")
                    .append(URLEncoder.encode(kvPairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------

    @Test
    void researcherSeesSubmitForm() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/submit");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Submit a run");
        assertThat(r.body()).contains("name=\"payload\"");
    }

    @Test
    void supervisorRedirectedFromSubmit() throws Exception {
        loginAs(SUP);
        HttpResponse<String> r = get("/submit");

        assertThat(r.statusCode())
                .as("supervisors can't submit; service would reject anyway")
                .isEqualTo(302);
        assertThat(r.headers().firstValue("Location").orElse(""))
                .doesNotContain("/submit");
    }

    @Test
    void anonymousRedirectedToLogin() throws Exception {
        HttpResponse<String> r = get("/submit");

        assertThat(r.statusCode()).isIn(302, 401);
    }

    @Test
    void validPayloadCreatesRun() throws Exception {
        loginAs(ALICE);

        HttpResponse<String> form = get("/submit");
        String csrf = csrfFrom(form.body());

        String payload = "{\"_source\":{\"file\":\"submitted-1.json\"},\"accuracy\":0.91}";
        String body = form("payload", payload, "batch", "submitted-batch")
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

        HttpResponse<String> post = postForm("/submit", body);
        assertThat(post.statusCode())
                .as("successful submit is a PRG redirect to the run detail page")
                .isEqualTo(302);

        String location = post.headers().firstValue("Location").orElse("");
        assertThat(location).contains("/runs/");

        // Location is absolute; extract the path for the follow-up GET.
        String path = URI.create(location).getPath();
        HttpResponse<String> detail = get(path);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("submitted-1.json");
    }

    @Test
    void payloadWithoutSourceFileIsRejected() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        String payload = "{\"accuracy\":0.91}";  // no _source.file
        String body = form("payload", payload)
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

        HttpResponse<String> post = postForm("/submit", body);
        assertThat(post.statusCode())
                .as("form re-renders with error, not a redirect")
                .isEqualTo(200);
        assertThat(post.body()).contains("_source");
        assertThat(post.body())
                .as("the submitted payload is preserved so the user can fix it")
                .contains("accuracy");
    }

    @Test
    void invalidJsonIsRejected() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        String body = form("payload", "{not valid json")
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

        HttpResponse<String> post = postForm("/submit", body);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("not valid JSON");
    }

    @Test
    void oversizedPayloadIsRejected() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        String big = "x".repeat(1_100_000);
        String body = form("payload", big)
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

        HttpResponse<String> post = postForm("/submit", body);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("exceeds");
    }

    @Test
    void validFileCreatesRun() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        String json = "{\"_source\":{\"file\":\"uploaded-1.json\"},\"accuracy\":0.88}";
        MultipartBody mp = multipart("uploaded-1.json", json,
                "_csrf", csrf,
                "batch", "uploaded-batch");

        HttpResponse<String> post = postMultipart("/submit/file", mp);
        assertThat(post.statusCode())
                .as("successful upload is a PRG redirect")
                .isEqualTo(302);

        String location = post.headers().firstValue("Location").orElse("");
        assertThat(location).contains("/runs/");

        HttpResponse<String> detail = get(URI.create(location).getPath());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("uploaded-1.json");
    }

    @Test
    void fileWithoutSourceFileIsRejected() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        String json = "{\"accuracy\":0.88}";
        MultipartBody mp = multipart("no-source.json", json, "_csrf", csrf);

        HttpResponse<String> post = postMultipart("/submit/file", mp);
        assertThat(post.statusCode())
                .as("form re-renders with error, not redirect")
                .isEqualTo(200);
        assertThat(post.body()).contains("_source");
    }

    @Test
    void fileWithInvalidJsonIsRejected() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom(get("/submit").body());

        MultipartBody mp = multipart("bad.json", "{not json", "_csrf", csrf);

        HttpResponse<String> post = postMultipart("/submit/file", mp);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("not valid JSON");
    }

    @Test
    void supervisorCannotUpload() throws Exception {
        loginAs(SUP);
        // Get CSRF from /submit first — but supervisor is redirected; use /saved instead.
        String csrf = csrfFrom(get("/saved").body());

        String json = "{\"_source\":{\"file\":\"x.json\"},\"accuracy\":0.5}";
        MultipartBody mp = multipart("x.json", json, "_csrf", csrf);

        HttpResponse<String> post = postMultipart("/submit/file", mp);
        assertThat(post.statusCode())
                .as("supervisors are redirected, not accepted")
                .isEqualTo(302);
        String location = post.headers().firstValue("Location").orElse("");
        assertThat(location).endsWith("/");
    }

    /**
     * Build a multipart/form-data body with one file part and N text fields.
     * Content is UTF-8. Boundary is fixed per-call — uniqueness within a
     * single request is all that matters.
     */
    private record MultipartBody(String contentType, byte[] body) {}

    private MultipartBody multipart(String fileName, String fileContent,
                                    String... textFieldPairs) throws Exception {
        String boundary = "----RunLedger" + UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/json" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(fileContent.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < textFieldPairs.length; i += 2) {
            String name = textFieldPairs[i];
            String value = textFieldPairs[i + 1];
            out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + name + "\""
                    + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        return new MultipartBody("multipart/form-data; boundary=" + boundary,
                out.toByteArray());
    }

    private HttpResponse<String> postMultipart(String path, MultipartBody mp) throws Exception {
        return client.send(
                HttpRequest.newBuilder().uri(uri(path))
                        .header("Content-Type", mp.contentType())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(mp.body()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}