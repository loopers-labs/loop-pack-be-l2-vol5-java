package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminBoundaryConfig {
    @Bean
    @Profile("!local")
    SecurityFilterChain adminBoundary(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(403)))
            .build();
    }

    @Bean
    @Profile("local")
    SecurityFilterChain localAdminBoundary(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api-admin/**"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(403)))
            .build();
    }
}
