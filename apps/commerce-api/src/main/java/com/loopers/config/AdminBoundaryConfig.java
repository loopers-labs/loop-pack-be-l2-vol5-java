package com.loopers.config;

import org.springframework.context.annotation.Bean;
import com.loopers.interfaces.api.AdminAccessDeniedResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminBoundaryConfig {
    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http, AdminAccessDeniedResponse denied) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> denied.write(response))
                .accessDeniedHandler((request, response, exception) -> denied.write(response)))
            .build();
    }
}
