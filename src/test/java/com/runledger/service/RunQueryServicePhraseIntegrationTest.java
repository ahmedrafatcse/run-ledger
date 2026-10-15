package com.runledger.service;

import com.runledger.entity.Run;
import com.runledger.security.AppSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the phrase/fuzzy native-query sort trap.
 *
 * <p>{@code searchByPhrase} and {@code searchByFuzzy} pass a {@code Pageable}
 * straight into a native query. If the {@code Pageable} carries a non-empty
 * {@code Sort}, the sort field name leaks into the SQL as written
 * (camelCase {@code createdAt}), which Postgres rejects — the column is
 * {@code created_at}. This shipped silently since Slice 2 because every
 * existing test either mocked the repository or ran through the CLI, which
 * doesn't sort.
 *
 * <p>The tests below use the exact {@code Pageable} shape
 * {@link com.runledger.controller.QueryController#phrase} builds, so any
 * regression in how sorts flow through native queries is caught before a
 * browser finds it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RunQueryServicePhraseIntegrationTest {

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

    @Autowired private RunQueryService runQueryService;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Pageable SORTED_PAGEABLE =
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));

    @BeforeEach
    void seed() throws Exception {
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

            insertRun(conn, "phrase-batch", "run-1.json",
                    "{\"notes\":\"weighted averaging experiment\"}");
            insertRun(conn, "phrase-batch", "run-2.json",
                    "{\"notes\":\"weighted regression baseline\"}");
            insertRun(conn, "phrase-batch", "run-3.json",
                    "{\"notes\":\"something unrelated\"}");
        }

        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                ALICE, "researcher", TEAM_A));
    }

    private void insertRun(Connection conn, String batch, String sourceFile, String payloadJson)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, canon_version, team_id, uploaded_by, batch)
                VALUES (?::jsonb, ?, 0, 1, true,
                        'placeholder-hash-phrase.json', 'v1',
                        '11111111-1111-1111-1111-111111111111',
                        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', ?)
                """)) {
            ps.setString(1, payloadJson);
            ps.setString(2, sourceFile);
            ps.setString(3, batch);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void clear() {
        AppSecurityContext.clear();
    }

    @Test
    void phraseSearch_withSortedPageable_returnsMatches() {
        Page<Run> result = runQueryService.searchByPhrase(
                "weighted", "phrase-batch", SORTED_PAGEABLE);

        assertThat(result.getContent())
                .as("both weighted notes must match; the third must not")
                .hasSize(2);
        assertThat(result.getContent())
                .extracting(Run::getSourceFile)
                .containsExactlyInAnyOrder("run-1.json", "run-2.json");
    }

    @Test
    void fuzzySearch_withSortedPageable_returnsMatches() {
        Page<Run> result = runQueryService.searchByFuzzy(
                "weightd", 0.3, "phrase-batch", SORTED_PAGEABLE);

        assertThat(result.getContent())
                .as("fuzzy match must tolerate the typo")
                .isNotEmpty();
    }
}