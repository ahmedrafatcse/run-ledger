package com.runledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * CSRF is enabled for HTML routes and disabled for the JSON API.
     *
     * <p>The API is header-authenticated ({@code X-User-Id}), so it has no
     * CSRF surface — an attacker's page can't set custom headers on a
     * cross-origin request. HTML forms are cookie-authenticated, which is
     * exactly what CSRF protection exists for.
     *
     * <p>Thymeleaf's {@code th:action} on a form emits the CSRF token
     * automatically (via Spring Security's request data value processor),
     * so no extra template work is needed as long as this filter chain
     * leaves CSRF enabled for those routes.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}