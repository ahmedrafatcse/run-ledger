package com.runledger.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@link SecuredTransactionTemplate} actually sets the Postgres
 * role and session variables before running the caller's work.
 *
 * <p>This is the verification the entire Slice 5 chain exists to produce:
 * after 5.1 through 5.5, every secured service method runs through this
 * wrapper, and this test class confirms the wrapper does what it claims.
 *
 * <p>Each test reads the three pieces of session state from inside a wrapped
 * transaction, so the values are guaranteed to reflect what a downstream
 * query would actually see - not what a second connection would see.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TransactionIdentityTest {

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
    private SecuredTransactionTemplate secured;

    @PersistenceContext
    private EntityManager entityManager;

    private static final UUID ALICE   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUP     = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ADMIN   = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @AfterEach
    void clearContext() {
        AppSecurityContext.clear();
    }

    /** Snapshot of the three session values the wrapper is responsible for. */
    private record SessionVars(String currentUser, String userId, String teamId) {}

    /**
     * Read all three values from inside a single wrapped transaction, so the
     * snapshot reflects the connection state a downstream query would see.
     */
    private SessionVars snapshot() {
        return secured.execute(() -> {
            String currentUser = (String) entityManager
                    .createNativeQuery("SELECT current_user")
                    .getSingleResult();
            String userId = (String) entityManager
                    .createNativeQuery("SELECT current_setting('app.current_user_id', true)")
                    .getSingleResult();
            String teamId = (String) entityManager
                    .createNativeQuery("SELECT current_setting('app.current_team_id', true)")
                    .getSingleResult();
            return new SessionVars(currentUser, userId, teamId);
        });
    }

    // ---------------------------------------------------------------
    // Role and identity binding
    // ---------------------------------------------------------------

    @Test
    void researcherIdentity_setsResearcherRoleWithUserAndTeam() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));

        SessionVars vars = snapshot();

        assertThat(vars.currentUser()).isEqualTo("runledger_researcher");
        assertThat(vars.userId()).isEqualTo(ALICE.toString());
        assertThat(vars.teamId()).isEqualTo(TEAM_A.toString());
    }

    @Test
    void supervisorIdentity_setsSupervisorRoleWithUserAndNoTeam() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(SUP, "supervisor", null));

        SessionVars vars = snapshot();

        assertThat(vars.currentUser()).isEqualTo("runledger_supervisor");
        assertThat(vars.userId()).isEqualTo(SUP.toString());
        // Supervisors have no single team, so the wrapper skips app.current_team_id.
        // An unset custom setting reads back as NULL with the missing_ok form.
        // Postgres initializes custom placeholder GUCs as empty string once
        // they've been set at least once in the session. After the local
        // scope resets, the value reverts to "" not NULL.
        assertThat(vars.teamId()).isNullOrEmpty();
    }

    @Test
    void adminIdentity_setsAdminRoleWithUserAndNoTeam() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ADMIN, "admin", null));

        SessionVars vars = snapshot();

        assertThat(vars.currentUser()).isEqualTo("runledger_admin");
        assertThat(vars.userId()).isEqualTo(ADMIN.toString());
        assertThat(vars.teamId()).isNull();
    }

    // ---------------------------------------------------------------
    // Fail-closed behaviour
    // ---------------------------------------------------------------

    @Test
    void withoutIdentity_throwsBeforeAnyWork() {
        AppSecurityContext.clear();

        assertThatThrownBy(() -> secured.execute(() -> "should never run"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No identity bound");
    }

    @Test
    void unknownRole_throws() {
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                UUID.randomUUID(), "nonsense", null));

        assertThatThrownBy(this::snapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown app role");
    }

    // ---------------------------------------------------------------
    // Isolation between transactions
    // ---------------------------------------------------------------

    @Test
    void settingIsScopedToTransaction_notPersistedAcross() {
        // First transaction as researcher
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(ALICE, "researcher", TEAM_A));
        SessionVars asResearcher = snapshot();
        assertThat(asResearcher.currentUser()).isEqualTo("runledger_researcher");

        // Second transaction as supervisor, different identity
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(SUP, "supervisor", null));
        SessionVars asSupervisor = snapshot();

        assertThat(asSupervisor.currentUser()).isEqualTo("runledger_supervisor");
        assertThat(asSupervisor.userId()).isEqualTo(SUP.toString());
        // If SET LOCAL / set_config(..., true) had leaked, this would still
        // show TEAM_A from the previous transaction.
        assertThat(asSupervisor.teamId()).isNullOrEmpty();
    }
}