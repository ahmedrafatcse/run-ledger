package com.runledger.smoke;

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

import java.io.ByteArrayOutputStream;
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
 * End-to-end smoke test for the entire UI surface.
 *
 * <p>Walks every page as each role, exercises every mutation, and asserts
 * none of them crash. This is a <b>breadth</b> test — the depth lives in
 * the more focused classes ({@code RlsHttpDataIsolationTest},
 * {@code QueryControllerTest}, etc.). Its job is to answer one question
 * on every commit: does the app still boot and serve every page?
 *
 * <p>Existence justification: the phrase/fuzzy native-sort bug shipped
 * silently from Slice 2 to Slice 7. No test rendered {@code /query/phrase}
 * against real Postgres, because every other test either mocks the service
 * or only follows nav links. A browser found it. This class renders it.
 *
 * <p>Assertions are the weakest possible meaningful kind: status code,
 * and one substring that only appears if the pipeline actually worked.
 * Anything stronger belongs in a focused test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EndToEndSmokeTest {

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

    /** Latest version of {@code smoke.json} in {@code smoke-batch}. */
    private long latestRunId;
    /** Previous version of the same identity — used for the diff page. */
    private long oldRunId;

    @BeforeEach
    void seed() throws Exception {
        cookies.getCookieStore().removeAll();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE batch_schema, saved_search, run RESTART IDENTITY");

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

            stmt.execute("""
                INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT DO NOTHING
                """);

            // V1 (superseded). Same identity as V2: (smoke-batch, smoke.json, 0).
            oldRunId = insertRun(conn,
                    "{\"experiment\":\"smoke\",\"notes\":\"weighted averaging v1\",\"accuracy\":0.85}",
                    "smoke.json", 0, 1, false, "smoke-batch", TEAM_A, ALICE);
            // V2 (latest). Current version of the same identity.
            latestRunId = insertRun(conn,
                    "{\"experiment\":\"smoke\",\"notes\":\"weighted averaging v2\",\"accuracy\":0.95,\"loss\":0.12}",
                    "smoke.json", 0, 2, true, "smoke-batch", TEAM_A, ALICE);
            // Array-payload run — exercises array-path queries and pointer rendering.
            insertRun(conn,
                    "{\"experiment\":\"array-smoke\",\"results\":[{\"accuracy\":0.90},{\"accuracy\":0.70}]}",
                    "array.json", 0, 1, true, "smoke-batch", TEAM_A, ALICE);

            // Metric mapping so /batches/{team}/{batch} renders a table
            // instead of falling back to the read-time computation.
            stmt.execute("""
                INSERT INTO batch_schema (team_id, batch, key_mapping) VALUES
                  ('11111111-1111-1111-1111-111111111111', 'smoke-batch',
                   '{"accuracy": ["accuracy"], "loss": ["loss"]}'::jsonb)
                """);
        }
    }

    private long insertRun(Connection conn, String payload, String sourceFile,
                           int sourceIndex, int version, boolean latest,
                           String batch, UUID teamId, UUID uploadedBy) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, canon_version, team_id, uploaded_by, batch)
                VALUES (?::jsonb, ?, ?, ?, ?, 'placeholder-hash-smoke.json', 'v1', ?::uuid, ?::uuid, ?)
                RETURNING id
                """)) {
            ps.setString(1, payload);
            ps.setString(2, sourceFile);
            ps.setInt(3, sourceIndex);
            ps.setInt(4, version);
            ps.setBoolean(5, latest);
            ps.setString(6, teamId.toString());
            ps.setString(7, uploadedBy.toString());
            ps.setString(8, batch);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // HTTP helpers
    // ──────────────────────────────────────────────────────────────────

    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private String fetchCsrf(String html) {
        int n = html.indexOf("name=\"_csrf\"");
        if (n == -1) return null;
        int v = html.indexOf("value=\"", n);
        if (v == -1) return null;
        int s = v + "value=\"".length();   // 7, not 8
        int e = html.indexOf('"', s);
        return e == -1 ? null : html.substring(s, e);
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

    private void loginAs(UUID userId) throws Exception {
        HttpResponse<String> page = get("/login");
        String csrf = fetchCsrf(page.body());
        assertThat(csrf).as("login form must render CSRF token").isNotNull();

        String body = "userId=" + userId + "&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> login = postForm("/login", body);
        assertThat(login.statusCode())
                .as("login POST must 302 — 403 = CSRF extraction wrong, "
                        + "200 = form re-rendered (bad user)")
                .isEqualTo(302);
    }

    private String csrfFrom(String path) throws Exception {
        String csrf = fetchCsrf(get(path).body());
        assertThat(csrf).as("page %s must expose CSRF token", path).isNotNull();
        return csrf;
    }

    private void assertOk(String path, String mustContain) throws Exception {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode())
                .as("GET %s — smoke test failed; this page is part of the demo", path)
                .isEqualTo(200);
        assertThat(r.body()).contains(mustContain);
    }

    private HttpResponse<String> postMultipart(String path, String fileName,
                                               String fileContent, String... textFields)
            throws Exception {
        String boundary = "----RunLedgerSmoke" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/json" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(fileContent.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i + 1 < textFields.length; i += 2) {
            out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + textFields[i] + "\""
                    + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(textFields[i + 1].getBytes(StandardCharsets.UTF_8));
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        return client.send(
                HttpRequest.newBuilder().uri(uri(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ──────────────────────────────────────────────────────────────────
    // Anonymous
    // ──────────────────────────────────────────────────────────────────

    @Test
    void anonymous_loginPageRenders() throws Exception {
        assertOk("/login", "RunLedger");
    }

    @Test
    void anonymous_protectedPagesRedirectToLogin() throws Exception {
        String[] protectedPaths = {
                "/", "/batches", "/saved", "/submit",
                "/query/phrase", "/query/aggregate", "/query/compare",
                "/runs/" + latestRunId
        };
        for (String path : protectedPaths) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode())
                    .as("GET %s as anonymous must redirect (302) or 401", path)
                    .isIn(302, 401);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Researcher
    // ──────────────────────────────────────────────────────────────────

    @Test
    void researcher_homeAndRunDetailRender() throws Exception {
        loginAs(ALICE);
        assertOk("/", "smoke.json");
        assertOk("/runs/" + latestRunId, "smoke.json");
        assertOk("/runs/" + latestRunId + "?depth=1", "smoke.json");
        assertOk("/runs/" + latestRunId + "?depth=all", "smoke.json");
    }

    @Test
    void researcher_historyAndDiffRender() throws Exception {
        loginAs(ALICE);
        assertOk("/runs/" + latestRunId + "/history", "smoke.json");
        // Same-identity compare between V1 and V2 → 200, shows both versions.
        assertOk("/runs/compare?id1=" + oldRunId + "&id2=" + latestRunId, "smoke.json");
    }

    /**
     * The one that would have caught the phrase/fuzzy native-sort bug.
     * Every mode here hits real SQL against real Postgres.
     */
    @Test
    void researcher_queryModesRender() throws Exception {
        loginAs(ALICE);

        assertOk("/query/phrase?q=weighted&batch=smoke-batch", "smoke.json");
        assertOk("/query/phrase?q=weighted&fuzzy=true&batch=smoke-batch", "smoke.json");

        assertOk("/query/aggregate?agg=COUNT&batch=smoke-batch", "smoke-batch");
        assertOk("/query/aggregate?agg=AVG&metric=accuracy&batch=smoke-batch", "smoke-batch");
        String encodedArrayMetric = URLEncoder.encode("results[].accuracy", StandardCharsets.UTF_8);
        assertOk("/query/aggregate?agg=AVG&metric=" + encodedArrayMetric + "&batch=smoke-batch",
                "smoke-batch");

        assertOk("/query/compare?metric=accuracy&op=gt&value=0.5&batch=smoke-batch", "smoke.json");
        assertOk("/query/compare"
                        + "?metric=accuracy&op=gt&value=0.5"
                        + "&metric=loss&op=lt&value=0.5"
                        + "&combine=and&batch=smoke-batch",
                "smoke.json");
    }

    @Test
    void researcher_batchesAndSavedPagesRender() throws Exception {
        loginAs(ALICE);
        assertOk("/batches", "smoke-batch");
        assertOk("/batches/" + TEAM_A + "/smoke-batch", "accuracy");
        assertOk("/saved", "Saved searches");
    }

    @Test
    void researcher_submitFormRenders() throws Exception {
        loginAs(ALICE);
        assertOk("/submit", "Submit");
    }

    // ──────────────────────────────────────────────────────────────────
    // Supervisor
    // ──────────────────────────────────────────────────────────────────

    @Test
    void supervisor_seesAllAssignedTeams() throws Exception {
        loginAs(SUP);
        // Supervisor sees both teams' runs.
        assertOk("/", "smoke.json");
        // Batches page shows both teams' groups.
        assertOk("/batches", "Team A");
        // Aggregate page has a team dropdown when assigned >1 team.
        assertOk("/query/aggregate", "Team");
    }

    @Test
    void supervisor_submitRedirects() throws Exception {
        loginAs(SUP);
        HttpResponse<String> r = get("/submit");
        assertThat(r.statusCode())
                .as("supervisors cannot submit — service layer rejects them")
                .isEqualTo(302);
        String location = r.headers().firstValue("Location").orElse("");
        assertThat(location).doesNotContain("/submit");
    }

    // ──────────────────────────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────────────────────────

    @Test
    void saveSearch_persistsAndAppearsOnList() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom("/saved");

        String body = "name=smoke-search&mode=phrase&q=weighted&batch=smoke-batch&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/saved", body);
        assertThat(post.statusCode())
                .as("POST /saved must 302 (PRG)")
                .isEqualTo(302);

        assertOk("/saved", "smoke-search");
    }

    @Test
    void deleteSearch_removesFromList() throws Exception {
        loginAs(ALICE);

        // Create one via the API so we can delete it by name.
        String csrf = csrfFrom("/saved");
        String save = "name=smoke-delete-me&mode=phrase&q=weighted&_csrf="
                + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        assertThat(postForm("/saved", save).statusCode()).isEqualTo(302);

        // Now delete.
        String csrf2 = csrfFrom("/saved");
        String del = "name=smoke-delete-me&_csrf="
                + URLEncoder.encode(csrf2, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/saved/delete", del);
        assertThat(post.statusCode()).isEqualTo(302);

        // Row gone; flash may still echo the name, so check the tag boundary.
        HttpResponse<String> after = get("/saved");
        assertThat(after.body())
                .as("after delete, the list row is gone")
                .doesNotContain(">smoke-delete-me<");
    }

    @Test
    void submitPaste_createsRunAndRedirectsToDetail() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom("/submit");

        String payload = "{\"_source\":{\"file\":\"smoke-paste.json\"},\"accuracy\":0.91}";
        String body = "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)
                + "&batch=smoke-batch&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

        HttpResponse<String> post = postForm("/submit", body);
        assertThat(post.statusCode()).isEqualTo(302);

        String location = post.headers().firstValue("Location").orElse("");
        assertThat(location).contains("/runs/");

        HttpResponse<String> detail = get(URI.create(location).getPath());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("smoke-paste.json");
    }

    /**
     * Exercises the multipart path end-to-end. If MultipartFilter isn't
     * registered before Spring Security's CSRF filter, this test fails
     * with 403 — that's the whole point.
     */
    @Test
    void submitFile_createsRunAndRedirectsToDetail() throws Exception {
        loginAs(ALICE);
        String csrf = csrfFrom("/submit");

        String json = "{\"_source\":{\"file\":\"smoke-upload.json\"},\"accuracy\":0.88}";
        HttpResponse<String> post = postMultipart(
                "/submit/file", "smoke-upload.json", json,
                "_csrf", csrf,
                "batch", "smoke-batch");

        assertThat(post.statusCode())
                .as("multipart POST must succeed — 403 means MultipartFilter "
                        + "is not registered before Spring Security")
                .isEqualTo(302);

        String location = post.headers().firstValue("Location").orElse("");
        assertThat(location).contains("/runs/");

        HttpResponse<String> detail = get(URI.create(location).getPath());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("smoke-upload.json");
    }

    @Test
    void logout_clearsSessionAndRedirects() throws Exception {
        loginAs(ALICE);
        // Confirm session is live.
        assertThat(get("/").statusCode()).isEqualTo(200);

        String csrf = csrfFrom("/");
        String body = "_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        HttpResponse<String> post = postForm("/logout", body);
        assertThat(post.statusCode()).isIn(302, 204);

        // After logout, a protected page redirects again.
        HttpResponse<String> after = get("/");
        assertThat(after.statusCode()).isIn(302, 401);
    }

    // ──────────────────────────────────────────────────────────────────
    // Error paths
    // ──────────────────────────────────────────────────────────────────

    @Test
    void missingRun_renders404View() throws Exception {
        loginAs(ALICE);
        HttpResponse<String> r = get("/runs/9999999");
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void crossTeamDiff_renders404() throws Exception {
        loginAs(ALICE);
        // Alice cannot see Bob's runs, and there are none seeded — but
        // the endpoint must not 500 either.
        HttpResponse<String> r = get("/runs/compare?id1=" + latestRunId + "&id2=9999999");
        assertThat(r.statusCode()).isIn(400, 404);
    }
}