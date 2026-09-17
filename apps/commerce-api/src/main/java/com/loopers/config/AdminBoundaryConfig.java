package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리자 API 접근 경계. `/api-admin/**` 은 ADMIN 역할을 요구하고,
 * 역할이 없는 사용자와 식별되지 않은 요청을 모두 403 으로 거절한다.
 * 이 경로에만 적용되므로 고객 API 의 `X-USER-ID` 식별 규칙은 그대로 유지된다.
 * 로컬 실습과 MockMvc 검증을 위한 경계이며 네트워크용 관리자 로그인은 제공하지 않는다.
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
