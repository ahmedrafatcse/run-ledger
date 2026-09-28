package com.runledger.security;

import com.runledger.entity.Run;
import com.runledger.repository.RunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the RLS policies defined in V13 enforce team isolation.
 *
 * <p>All five tests run through {@link SecuredTransactionTemplate}, which
 * sets the Postgres role and {@code app.current_user_id} for the transaction.
 * The queries then hit the RLS policies, which filter rows by team membership.
 *
 * <p>Setup inserts runs directly via the owner connection (bypassing RLS)
 * so the test knows exactly what rows exist. Read-back happens through the
 * app role, so any filtering is done by the policies under test.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RlsIsolationTest {

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

    @Autowired private RunRepository runRepository;
    @Autowired private SecuredTransactionTemplate secured;

    // Identity fixtures (must match the seeded rows in @BeforeEach)
    private static final UUID ALICE   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB     = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SUP     = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SUP_B   = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID UNKNOWN = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final UUID TEAM_A  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B  = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * Seed teams, users, assignments, and two runs (one per team).
     *
     * <p>The run inserts run as the owner (via a direct JDBC connection),
     * which bypasses RLS. This is intentional: the test needs known rows
     * in the table regardless of any policy. The read-back happens through
     * the app role via the wrapper, so the policies do all the filtering.
     */
    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Runs are truncated each test so count assertions are exact.
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
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL),
                  ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'supB@example.com',  'SupB',  'supervisor', NULL)
                ON CONFLICT (id) DO NOTHING
                """);

            stmt.execute("""
                INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222'),
                  ('dddddddd-dddd-dddd-dddd-dddddddddddd', '22222222-2222-2222-2222-222222222222')
                ON CONFLICT DO NOTHING
                """);

            // Two runs, one per team. Inserted as owner to bypass RLS.
            stmt.execute("""
                INSERT INTO run (payload, source_file, source_index, version, latest,
                                 payload_hash, team_id, uploaded_by) VALUES
                  ('{"experiment":"team_a_run"}'::jsonb, 'a.json', 0, 1, true,
                   'hash_a', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
                  ('{"experiment":"team_b_run"}'::jsonb, 'b.json', 0, 1, true,
                   'hash_b', '22222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb')
                """);
        }
    }

    @AfterEach
    void clearIdentity() {
        AppSecurityContext.clear();
    }

    // -----------------------------------------------------------------
    // 1. Researcher sees their own team's runs
    // -----------------------------------------------------------------

    @Test
    void researcher_seesOwnTeamRuns() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        List<Run> visible = secured.execute(() -> runRepository.findAll());

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getTeamId()).isEqualTo(TEAM_A);
    }

    // -----------------------------------------------------------------
    // 2. Researcher cannot see another team's runs
    // -----------------------------------------------------------------

    @Test
    void researcher_cannotSeeOtherTeamRuns() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        List<Run> visible = secured.execute(() -> runRepository.findAll());

        assertThat(visible)
                .extracting(Run::getTeamId)
                .containsExactly(TEAM_A)
                .doesNotContain(TEAM_B);
    }

    // -----------------------------------------------------------------
    // 3. Supervisor sees all assigned teams
    // -----------------------------------------------------------------

    @Test
    void supervisor_seesAllAssignedTeams() {
        // Sup is assigned to Teams A and B.
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(SUP, "supervisor", null));

        List<Run> visible = secured.execute(() -> runRepository.findAll());

        assertThat(visible).hasSize(2);
        assertThat(visible).extracting(Run::getTeamId)
                .containsExactlyInAnyOrder(TEAM_A, TEAM_B);
    }

    // -----------------------------------------------------------------
    // 4. Supervisor sees nothing for unassigned teams
    // -----------------------------------------------------------------

    @Test
    void supervisor_cannotSeeUnassignedTeams() {
        // SupB is assigned to Team B only, so Team A's run must be invisible.
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(SUP_B, "supervisor", null));

        List<Run> visible = secured.execute(() -> runRepository.findAll());

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getTeamId()).isEqualTo(TEAM_B);
    }

    // -----------------------------------------------------------------
    // 5. Unknown identity sees nothing (RLS layer fails closed)
    // -----------------------------------------------------------------

    @Test
    void unknownIdentity_seesNothing() {
        // Simulates a session where the wrapper runs but the identity UUID
        // doesn't correspond to any app_users row. The filter would normally
        // reject this at the HTTP layer; here we test the RLS layer directly.
        // The policy resolves the user's team via app_users, finds nothing,
        // and the row set is empty. This is the fail-closed behaviour.
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(UNKNOWN, "researcher", null));

        List<Run> visible = secured.execute(() -> runRepository.findAll());

        assertThat(visible).isEmpty();
    }
}