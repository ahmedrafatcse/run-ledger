package com.runledger.repository;

import com.runledger.entity.Run;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class RunRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16"))
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

    @Autowired
    private RunRepository runRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String ALICE  = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TEAM_A = "11111111-1111-1111-1111-111111111111";

    private Run runA;
    private Run runB;
    private Run runC;
    private Run deepRun;
    private Run phraseRun;
    private Run fuzzyRun;

    /**
     * Fixture setup for a repository test that must run under RLS.
     *
     * <p>(1) Fixtures are inserted via a direct owner JDBC connection, which
     * bypasses RLS. The app role cannot insert runs without a bound identity,
     * and this test class does not use SecuredTransactionTemplate.
     *
     * <p>(2) The test transaction is switched to runledger_researcher via
     * SET LOCAL ROLE, plus set_config for app.current_user_id and
     * app.current_team_id. This makes the SELECT policy on run match Team A
     * rows, which is where every fixture is assigned.
     *
     * <p>All fixtures are inserted here, in @BeforeEach, rather than mid-test.
     * Under READ COMMITTED (the current default) mid-test inserts work because
     * each statement sees a fresh snapshot. Under REPEATABLE READ or
     * SERIALIZABLE they would be invisible to the test's already-open
     * transaction. Folding them here removes that hidden dependency at no
     * cost.
     *
     * <p>The phrase and fuzzy fixtures deliberately contain disjoint text
     * ("quick brown fox" vs. "weighted averaging") so their respective
     * search tests cannot cross-match. Sharing text would make the phrase
     * test pass via the fuzzy mechanism and vice versa, which would not
     * prove what either test is meant to prove.
     */
    @BeforeEach
    void setUp() throws Exception {
        // ---- (1) Fixture data via owner connection ----
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

            runA = partialRun(insertRunAsOwner(conn,
                    "{\"experiment\":\"A\",\"metrics\":{\"accuracy\":0.95,\"loss\":0.10}}"));
            runB = partialRun(insertRunAsOwner(conn,
                    "{\"experiment\":\"B\",\"metrics\":{\"accuracy\":0.80,\"loss\":0.25}}"));
            runC = partialRun(insertRunAsOwner(conn,
                    "{\"experiment\":\"C\",\"metrics\":{\"accuracy\":0.91,\"loss\":0.15,\"status\":\"completed\"}}"));

            deepRun = partialRun(insertRunAsOwner(conn,
                    "{\"config\":{\"optimizer\":{\"settings\":{\"learning_rate\":0.0001}}}}"));
            phraseRun = partialRun(insertRunAsOwner(conn,
                    "{\"experiment\":\"phrase_test\",\"notes\":\"the quick brown fox jumps over the lazy dog\"}"));
            fuzzyRun = partialRun(insertRunAsOwner(conn,
                    "{\"experiment\":\"fuzzy_test\",\"notes\":\"applied weighted averaging\"}"));
        }

        // ---- (2) Switch the test transaction to the researcher role ----
        entityManager.createNativeQuery("SET LOCAL ROLE runledger_researcher").executeUpdate();

        // Guard: fail loudly if the role did not take effect. Without this,
        // a broken grant or missing membership surfaces as "0 rows returned"
        // three layers away from the cause.
        String currentUser = (String) entityManager
                .createNativeQuery("SELECT current_user")
                .getSingleResult();
        assertThat(currentUser)
                .as("test transaction must run as runledger_researcher")
                .isEqualTo("runledger_researcher");

        entityManager.createNativeQuery(
                        "SELECT set_config('app.current_user_id', :uid, true)")
                .setParameter("uid", ALICE)
                .getSingleResult();
        entityManager.createNativeQuery(
                        "SELECT set_config('app.current_team_id', :tid, true)")
                .setParameter("tid", TEAM_A)
                .getSingleResult();
    }

    private Run partialRun(Long id) {
        Run r = new Run();
        r.setId(id);
        return r;
    }

    private Long insertRunAsOwner(Connection conn, String payloadJson) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, team_id, uploaded_by)
                VALUES (?::jsonb, ?, 0, 1, true, ?,
                        '11111111-1111-1111-1111-111111111111',
                        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')
                RETURNING id
                """)) {
            ps.setString(1, payloadJson);
            ps.setString(2, "test.json");
            ps.setString(3, "hash-" + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    void shouldFindRunsWithMetricGreaterThan() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.accuracy", 0.9, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());
    }

    @Test
    void greaterThan_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.accuracy", 0.99, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void shouldFindRunsWithMetricLessThan() {
        Page<Run> result = runRepository.findByMetricLessThan(
                "metrics.loss", 0.20, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactlyInAnyOrder(runA.getId(), runC.getId());
    }

    @Test
    void shouldFindRunsWithMetricEqualToNumber() {
        Page<Run> result = runRepository.findByMetricEquals(
                "metrics.accuracy", 0.91, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    @Test
    void shouldFindRunsWithTextMetricEquals() {
        Page<Run> result = runRepository.findByMetricEqualsText(
                "metrics.status", "completed", Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(runC.getId());
    }

    @Test
    void textEquals_noMatch_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricEqualsText(
                "metrics.status", "running", Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void metricNotPresent_returnsEmpty() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "metrics.nonexistent", 0.5, Pageable.unpaged());
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void shouldQueryDeeplyNestedPath() {
        Page<Run> result = runRepository.findByMetricGreaterThan(
                "config.optimizer.settings.learning_rate", 0.00001, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(deepRun.getId());

        Page<Run> emptyResult = runRepository.findByMetricGreaterThan(
                "config.optimizer.settings.learning_rate", 0.1, Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }

    @Test
    void shouldFindRunsByPhrase() {
        Page<Run> result = runRepository.searchByPhrase("quick brown fox", Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(phraseRun.getId());

        Page<Run> emptyResult = runRepository.searchByPhrase("random phrase", Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }

    @Test
    void shouldFindRunsByFuzzyMatch() {
        Page<Run> result = runRepository.searchByFuzzy("weighted avaraging", 0.3, Pageable.unpaged());
        assertThat(result.getContent()).extracting(Run::getId)
                .containsExactly(fuzzyRun.getId());

        Page<Run> emptyResult = runRepository.searchByFuzzy("random", 0.3, Pageable.unpaged());
        assertThat(emptyResult.getContent()).isEmpty();
    }
}