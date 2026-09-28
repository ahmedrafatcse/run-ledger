package com.runledger.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the column-level grants from Slice 3, now under RLS.
 *
 * <p>The test transaction runs as {@code runledger_researcher}, which has
 * SELECT, INSERT, and UPDATE (latest) on run, plus the corresponding RLS
 * policies from V13. Fixtures are inserted via a direct owner connection
 * so they exist regardless of RLS; the assertions then run as the researcher
 * role and exercise the grants.
 *
 * <p>The distinction being tested is grant-level, not policy-level:
 * "permission denied" means the role lacks the column privilege.
 * "new row violates row-level security policy" means the role has the
 * privilege but the row does not match the policy. The tests below assert
 * on the former, so the transaction must run as a role where the grants
 * are the binding constraint.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
@Transactional
class RunImmutabilityTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String ALICE  = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TEAM_A = "11111111-1111-1111-1111-111111111111";

    /**
     * Seed teams/users (owner connection) and switch the test transaction
     * to runledger_researcher so the grant assertions run as the app's
     * researcher role.
     */
    @BeforeEach
    void setUp() throws Exception {
        // Seed reference data via owner connection. Idempotent so safe to
        // run before every test.
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

        // Switch the test transaction's role to researcher.
        entityManager.createNativeQuery("SET LOCAL ROLE runledger_researcher").executeUpdate();

        // Guard: fail loudly if the role didn't take effect. Without this,
        // a broken grant or missing membership surfaces as a generic policy
        // denial rather than a clear "role is wrong" message.
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

    // ---------------------------------------------------------------
    // Rejected: grant-level denials
    // ---------------------------------------------------------------

    @Test
    void updatePayload_isRejected() throws Exception {
        Long id = insertFixtureAsOwner();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE run SET payload = ?::jsonb WHERE id = ?",
                        "{\"tampered\": true}", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void updatePayloadHash_isRejected() throws Exception {
        Long id = insertFixtureAsOwner();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE run SET payload_hash = ? WHERE id = ?",
                        "deadbeef", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void deleteRun_isRejected() throws Exception {
        Long id = insertFixtureAsOwner();

        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM run WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    // ---------------------------------------------------------------
    // Permitted: grant allows, policy matches
    // ---------------------------------------------------------------

    @Test
    void updateLatest_isPermitted() throws Exception {
        Long id = insertFixtureAsOwner();

        int rows = jdbcTemplate.update(
                "UPDATE run SET latest = false WHERE id = ?", id);
        assertThat(rows).isEqualTo(1);

        Boolean latest = jdbcTemplate.queryForObject(
                "SELECT latest FROM run WHERE id = ?", Boolean.class, id);
        assertThat(latest).isFalse();
    }

    @Test
    void insertNewRun_isPermitted() {
        // Insert as the researcher role. The row must satisfy the RLS insert
        // policy: team_id must match the identity's team, and uploaded_by
        // must match the identity's user ID.
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, team_id, uploaded_by)
                VALUES (?::jsonb, ?, 0, 1, true, ?, ?::uuid, ?::uuid)
                RETURNING id
                """,
                Long.class,
                "{\"test\": true}",
                "immutability-insert.json",
                "hash-insert-" + System.nanoTime(),
                TEAM_A,
                ALICE);

        assertThat(id).isNotNull().isPositive();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run WHERE id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Fixture helper
    // ---------------------------------------------------------------

    /**
     * Insert a fixture row via a direct owner connection, bypassing RLS.
     * The test transaction (which runs as runledger_researcher) will see
     * the committed row under READ COMMITTED.
     */
    private Long insertFixtureAsOwner() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, team_id, uploaded_by)
                VALUES (?::jsonb, ?, 0, 1, true, ?, ?::uuid, ?::uuid)
                RETURNING id
                """)) {
            ps.setString(1, "{\"test\": true}");
            ps.setString(2, "immutability-fixture-" + System.nanoTime() + ".json");
            ps.setString(3, "hash-fixture-" + System.nanoTime());
            ps.setString(4, TEAM_A);
            ps.setString(5, ALICE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}