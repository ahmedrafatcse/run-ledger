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
 * Proves that RLS session state does not leak between transactions on a
 * reused connection.
 *
 * <p>HikariCP is configured with a single-connection pool so every wrapped
 * call reuses the same physical connection. If {@code SET LOCAL ROLE} or
 * {@code set_config(..., true)} leaked past the transaction boundary, the
 * second call in a sequence would see rows it should not.
 *
 * <p>This is the RLS-specific sibling of the pool-leak verification done in
 * earlier slices. The property being tested is: after a wrapped transaction
 * commits, the next wrapped transaction starts from a clean session state,
 * regardless of what the previous one set.
 *
 * <p>If this test fails, the failure mode is one user's team access bleeding
 * into another user's request on the same connection. That is exactly the
 * class of bug RLS is meant to eliminate, so a failure here means the
 * enforcement is not actually as strong as the isolation tests suggest.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RlsPoolLeakTest {

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

        // Single-connection pool: every wrapped call reuses the same physical
        // connection. Makes the pool-leak property deterministic.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Autowired private RunRepository runRepository;
    @Autowired private SecuredTransactionTemplate secured;

    private static final UUID ALICE  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TEAM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void seed() throws Exception {
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
    // The pool-leak test
    // -----------------------------------------------------------------

    @Test
    void sequentialWrappedCalls_withDifferentIdentities_doNotLeak() {
        // ---- Request 1: Alice, Team A ----
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        List<Run> aliceView = secured.execute(() -> runRepository.findAll());

        assertThat(aliceView)
                .as("Alice should see only Team A's run on her first request")
                .hasSize(1);
        assertThat(aliceView.get(0).getTeamId()).isEqualTo(TEAM_A);

        // ---- Request 2: Bob, Team B, on the same physical connection ----
        // If SET LOCAL ROLE or set_config(..., true) leaked past commit,
        // Bob would either see Alice's row, or the role would still be
        // runledger_researcher, causing policy mismatches.
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(BOB, "researcher", TEAM_B));

        List<Run> bobView = secured.execute(() -> runRepository.findAll());

        assertThat(bobView)
                .as("Bob should see only Team B's run - no leakage from Alice's request")
                .hasSize(1);
        assertThat(bobView.get(0).getTeamId()).isEqualTo(TEAM_B);

        // ---- Request 3: back to Alice ----
        // Ensures the second request didn't somehow corrupt session state
        // for subsequent reuse.
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        List<Run> aliceAgainView = secured.execute(() -> runRepository.findAll());

        assertThat(aliceAgainView)
                .as("Alice should see Team A's run again after Bob's request")
                .hasSize(1);
        assertThat(aliceAgainView.get(0).getTeamId()).isEqualTo(TEAM_A);
    }
}