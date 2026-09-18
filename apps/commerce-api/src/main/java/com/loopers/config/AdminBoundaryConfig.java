package com.loopers.config;

import com.loopers.interfaces.api.commerce.CommerceErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminBoundaryConfig {
    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http, CommerceErrors errors) throws Exception {
        CommerceErrors.Failure forbidden = new CommerceErrors.Failure(403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, exception) -> errors.write(response, forbidden))
                .accessDeniedHandler((request, response, exception) -> errors.write(response, forbidden)))
            .build();
    }
}
