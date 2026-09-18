package com.runledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Development/test identity filter.
 *
 * <p>Reads {@code X-User-Id} from the request, looks up the user in the
 * {@code app_users} table, and binds the derived identity (user id, role,
 * team) to {@link AppSecurityContext} for the duration of the request.
 *
 * <p>The client supplies only a user id. Role and team come from the
 * database — the client is never trusted to declare its own authorization
 * context.
 *
 * <p>Only active under the {@code dev} and {@code test} profiles. In
 * production, a real authentication mechanism replaces this filter and
 * populates {@link AppSecurityContext} the same way.
 */
@Component
@Profile({"dev", "test"})
public class IdentityFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdentityFilter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Skip actuator endpoints so health checks and metrics don't require an
     * identity. Everything else under the servlet context goes through the
     * filter.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/error")
                || path.equals("/favicon.ico");
    }

    /**
     * Skip error dispatches: by the time an error reaches the container, the
     * original request has already been processed (and either passed through
     * the filter or been rejected by it). Re-running identity resolution on
     * the error dispatch is unnecessary and can hide the original failure.
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

        // 1. Extract the header
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing X-User-Id header.");
            return;
        }

        // 2. Parse it as a UUID
        UUID userId;
        try {
            userId = UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "X-User-Id must be a valid UUID.");
            return;
        }

        // 3. Look up the user. Role and team come from the database — never
        //    from the client.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT app_role, team_id FROM app_users WHERE id = ?", userId);

        if (rows.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Unknown user identity.");
            return;
        }

        Map<String, Object> row = rows.get(0);
        String role = (String) row.get("app_role");
        Object teamIdRaw = row.get("team_id");
        UUID teamId = (teamIdRaw == null)
                ? null
                : UUID.fromString(teamIdRaw.toString());

        // 4. Bind the identity for this request only
        AppSecurityContext.set(new AppSecurityContext.UserPrincipal(userId, role, teamId));
        try {
            chain.doFilter(request, response);
        } finally {
            AppSecurityContext.clear();
        }
    }

    /**
     * Write a minimal JSON error body. Mirrors the shape used by
     * {@code GlobalExceptionHandler} so clients see a consistent format.
     */
    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = Map.of(
                "error", status == HttpServletResponse.SC_UNAUTHORIZED
                        ? "Unauthorized" : "Bad Request",
                "message", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}