package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리자 경로(/api-admin/**)만 ROLE_ADMIN으로 제한하는 로컬 실습용 지원 설정.
 * 네트워크용 관리자 로그인은 제공하지 않으며, 관리자 API는 MockMvc에서 권한을 붙여 실행한다(T-6).
 */
@Configuration
public class AdminBoundaryConfig {
    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(403)))
            .build();
    }
}
