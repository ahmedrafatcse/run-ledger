package com.runledger.config;

import com.runledger.security.IdentityFilter;
import com.runledger.security.SessionIdentityFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers both identity filters against {@code /*}, with each filter
 * deciding internally which paths it applies to.
 *
 * <p>Why not scope each filter to its own path list: the servlet URL pattern
 * of {@code "/"} doesn't mean "the root path", it means "the default servlet
 * mapping" — everything that isn't matched by a more specific pattern. Using
 * {@code "/"} for the session filter meant it ran on {@code /login} and
 * redirected to {@code /login} in a loop.
 *
 * <p>Instead, both filters are registered against {@code /*} (every request)
 * and skip paths they don't handle, using {@code shouldNotFilter}. The
 * behavior is explicit and testable; the servlet container isn't doing any
 * pattern magic.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<IdentityFilter> identityFilterRegistration(
            IdentityFilter filter) {
        FilterRegistrationBean<IdentityFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<SessionIdentityFilter> sessionIdentityFilterRegistration(
            SessionIdentityFilter filter) {
        FilterRegistrationBean<SessionIdentityFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(20);
        return reg;
    }
}