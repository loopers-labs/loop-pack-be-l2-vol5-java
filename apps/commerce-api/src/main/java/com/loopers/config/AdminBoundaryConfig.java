package com.loopers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.security.AdminAuthenticationFilter;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.ErrorType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class AdminBoundaryConfig {
    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http, UserService userService, ObjectMapper objectMapper) throws Exception {
        AdminAuthenticationFilter adminFilter = new AdminAuthenticationFilter(userService, objectMapper);
        return http.securityMatcher("/api-admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(adminFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                adminFilter.writeFailure(response, ErrorType.NOT_ADMIN, ErrorType.NOT_ADMIN.getMessage()))
                        .accessDeniedHandler((request, response, exception) ->
                                adminFilter.writeFailure(response, ErrorType.NOT_ADMIN, ErrorType.NOT_ADMIN.getMessage())))
                .build();
    }
}
