package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminBoundaryConfig {

    private static final AuthenticationEntryPoint ALWAYS_FORBIDDEN =
        (request, response, exception) -> response.sendError(403);

    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .httpBasic(basic -> basic.authenticationEntryPoint(ALWAYS_FORBIDDEN))
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors.authenticationEntryPoint(ALWAYS_FORBIDDEN))
            .build();
    }
}
