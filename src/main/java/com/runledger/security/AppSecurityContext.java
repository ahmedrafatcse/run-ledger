package com.runledger.security;

import java.util.UUID;

/**
 * Thread-local storage for the current request's identity.
 *
 * <p>Populated by {@link IdentityFilter} at the start of each request and
 * cleared in a {@code finally} block when the request returns. The team and
 * role are always derived from the database ({@code app_users}), never from
 * client-supplied headers.
 *
 * <p>This is the source of truth for {@code app.current_user_id} that
 * transaction-scoped RLS (Slice 5 and 6) will consume.
 */
public final class AppSecurityContext {

    /**
     * Identity of the current request.
     *
     * @param userId the UUID of the authenticated user (never null)
     * @param role   the user's application role: researcher, supervisor, or admin
     * @param teamId the user's team UUID; null for supervisors and admins
     */
    public record UserPrincipal(UUID userId, String role, UUID teamId) {
        public boolean isResearcher()  { return "researcher".equals(role); }
        public boolean isSupervisor()  { return "supervisor".equals(role); }
        public boolean isAdmin()       { return "admin".equals(role); }
    }

    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private AppSecurityContext() {
        // utility class — no instances
    }

    /**
     * Bind a principal to the current thread. Must be called inside a request
     * lifecycle, and paired with {@link #clear()} in a finally block.
     */
    public static void set(UserPrincipal principal) {
        CURRENT.set(principal);
    }

    /**
     * Return the current principal, or null if no identity is bound.
     */
    public static UserPrincipal get() {
        return CURRENT.get();
    }

    /**
     * Return the current user's UUID, or null if no identity is bound.
     */
    public static UUID getUserId() {
        UserPrincipal p = CURRENT.get();
        return p == null ? null : p.userId();
    }

    /**
     * Return the current user's role, or null if no identity is bound.
     */
    public static String getRole() {
        UserPrincipal p = CURRENT.get();
        return p == null ? null : p.role();
    }

    /**
     * Return the current user's team UUID, or null if no identity is bound
     * or the user is a supervisor.
     */
    public static UUID getTeamId() {
        UserPrincipal p = CURRENT.get();
        return p == null ? null : p.teamId();
    }

    /**
     * Return the current principal, throwing if none is bound.
     *
     * <p>Used by code paths that must fail closed — a missing identity
     * indicates a request bypassed the filter, which is never acceptable
     * for a secured operation.
     *
     * @throws IllegalStateException if no identity is bound to this thread
     */
    public static UserPrincipal require() {
        UserPrincipal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException(
                    "No identity bound to current thread. "
                            + "Was IdentityFilter bypassed, or is the current profile not 'dev' or 'test'?");
        }
        return p;
    }

    /**
     * Remove the current thread's identity. Always call this in a finally block
     * so the thread pool doesn't leak context between requests.
     */
    public static void clear() {
        CURRENT.remove();
    }
}