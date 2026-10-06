package com.runledger.config;

import com.runledger.security.IdentityFilter;
import com.runledger.security.SessionIdentityFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit path scoping for the two identity filters.
 *
 * <p>Without this, both filters would be auto-registered by Spring Boot
 * against {@code /*} — every path — because both are {@code @Component}s.
 * On a request to {@code /api/runs}, both would run: IdentityFilter would
 * populate the context from the header, then SessionIdentityFilter would
 * find no session and redirect the CLI's request to {@code /login}. Every
 * integration test that hits the API would break with an HTML redirect
 * instead of JSON.
 *
 * <p>Registering each filter against a disjoint path list makes the collision
 * impossible rather than a corner case to remember. Each filter literally
 * cannot run on the other's paths.
 */
@Configuration
public class FilterConfig {

    /**
     * Header-based identity, scoped to the JSON API.
     * The CLI and any direct API client use this path.
     */
    @Bean
    public FilterRegistrationBean<IdentityFilter> identityFilterRegistration(
            IdentityFilter filter) {
        FilterRegistrationBean<IdentityFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/*");
        reg.setOrder(10);
        return reg;
    }

    /**
     * Session-based identity, scoped to browser pages.
     * Does not include {@code /login} or {@code /logout}: those paths are
     * the entry and exit points of the session lifecycle and must not
     * require a session to run.
     */
    @Bean
    public FilterRegistrationBean<SessionIdentityFilter> sessionIdentityFilterRegistration(
            SessionIdentityFilter filter) {
        FilterRegistrationBean<SessionIdentityFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/", "/runs/*");
        reg.setOrder(20);
        return reg;
    }
}