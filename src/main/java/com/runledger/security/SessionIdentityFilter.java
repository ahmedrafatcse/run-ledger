package com.runledger.security;

import com.runledger.entity.AppUser;
import com.runledger.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Development identity filter for browser pages.
 *
 * <p>Reads a user ID from the HTTP session (set by LoginController) and
 * populates {@link AppSecurityContext} for the request. This is the browser
 * counterpart to {@link IdentityFilter}, which reads {@code X-User-Id} from
 * request headers for API calls.
 *
 * <p>The two filters are registered against disjoint path lists (see
 * {@link com.runledger.config.FilterConfig}), so they can never both run
 * on the same request. That's deliberate: two identity sources on the same
 * path would need a documented precedence rule, and the answer would be
 * whatever order the servlet container happened to pick.
 *
 * <p>Only active under the {@code dev} and {@code test} profiles. The
 * session it reads from is populated by the development login stub; in
 * production, a real authentication mechanism would populate the session
 * the same way, and this filter would be unchanged.
 */
@Component
@Profile({"dev", "test"})
public class SessionIdentityFilter extends OncePerRequestFilter {

    private static final String SESSION_KEY = "userId";

    private final AppUserRepository appUserRepository;

    public SessionIdentityFilter(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * SessionIdentityFilter handles browser pages only. It skips:
     * <ul>
     *   <li>{@code /api/*} — {@link IdentityFilter} handles API calls.</li>
     *   <li>{@code /login} and {@code /logout} — these are the entry and
     *       exit points of the session lifecycle and must not require
     *       a session to run.</li>
     *   <li>static assets and actuator paths — no identity needed.</li>
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/")
                || path.equals("/login")
                || path.equals("/logout")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/actuator")
                || path.startsWith("/error")
                || path.equals("/favicon.ico");
    }

    /**
     * Skip error dispatches: by the time an error reaches the container,
     * the original request has already been processed. Re-running identity
     * resolution on the error dispatch would either duplicate work or hide
     * the original failure behind a redirect.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        Object userId = (session != null) ? session.getAttribute(SESSION_KEY) : null;

        if (userId == null) {
            // No session. Browser pages require identity; redirect to login.
            response.sendRedirect("/login");
            return;
        }

        // Fail closed if the session points at a user that no longer exists.
        // Invalidating the session and redirecting is the recoverable behavior;
        // throwing a 500 would leave the user stuck.
        Optional<AppUser> user;
        try {
            user = appUserRepository.findById((UUID) userId);
        } catch (ClassCastException e) {
            // Session attribute isn't a UUID. Invalidate and start over.
            session.invalidate();
            response.sendRedirect("/login");
            return;
        }

        if (user.isEmpty()) {
            session.invalidate();
            response.sendRedirect("/login");
            return;
        }

        AppUser u = user.get();
        try {
            AppSecurityContext.set(new AppSecurityContext.UserPrincipal(
                    u.getId(), u.getAppRole(), u.getTeamId()));
            chain.doFilter(request, response);
        } finally {
            AppSecurityContext.clear();
        }
    }
}