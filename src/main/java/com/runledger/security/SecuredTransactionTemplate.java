package com.runledger.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs a unit of work inside a transaction that has the current request's
 * identity bound to the database session.
 *
 * <p>Every secured service operation must go through this template, which:
 * <ol>
 *   <li>requires an identity bound to {@link AppSecurityContext};</li>
 *   <li>maps the app role to the corresponding Postgres role;</li>
 *   <li>issues {@code SET LOCAL ROLE} and {@code set_config} for the user
 *       (and, for researchers, the team) scoped to this transaction only;</li>
 *   <li>invokes the caller's work inside the same transaction;</li>
 *   <li>commits, releasing the role and session variables.</li>
 * </ol>
 *
 * <p>Fail-closed: if no identity is bound, the call throws
 * {@link IllegalStateException} and no database work occurs. This is the
 * enforcement boundary that Slice 6's RLS policies depend on.
 *
 * <p>Startup and background jobs must not use this template. They run
 * through an explicit, separate system path with its own review - see
 * git history for the removed PayloadHashBackfill as the example class of
 * component this applies to.
 */
@Component
public class SecuredTransactionTemplate {

    private static final String ROLE_RESEARCHER = "runledger_researcher";
    private static final String ROLE_SUPERVISOR = "runledger_supervisor";
    private static final String ROLE_ADMIN      = "runledger_admin";

    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public SecuredTransactionTemplate(PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Run {@code work} inside a transaction scoped to the current identity.
     *
     * @throws IllegalStateException if no identity is bound to the current thread
     */
    public <T> T execute(Supplier<T> work) {
        return txTemplate.execute(status -> {
            AppSecurityContext.UserPrincipal p = AppSecurityContext.require();

            String pgRole = switch (p.role()) {
                case "researcher" -> ROLE_RESEARCHER;
                case "supervisor" -> ROLE_SUPERVISOR;
                case "admin"      -> ROLE_ADMIN;
                default -> throw new IllegalStateException(
                        "Unknown app role: " + p.role());
            };

            // SET LOCAL ROLE must be string-concatenated: Postgres does not
            // accept bind parameters for role names. pgRole is derived from a
            // fixed enum mapping above, never from user input.
            entityManager.createNativeQuery("SET LOCAL ROLE " + pgRole)
                    .executeUpdate();

            entityManager.createNativeQuery(
                            "SELECT set_config('app.current_user_id', :uid, true)")
                    .setParameter("uid", p.userId().toString())
                    .getSingleResult();

            if (p.teamId() != null) {
                entityManager.createNativeQuery(
                                "SELECT set_config('app.current_team_id', :tid, true)")
                        .setParameter("tid", p.teamId().toString())
                        .getSingleResult();
            }

            return work.get();
        });
    }
}