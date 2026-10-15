package com.loopers.acceptance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인수 테스트 전용 관리자 인증 수단.
 *
 * <p>운영의 {@code AdminBoundaryConfig}는 {@code /api-admin/**}에 ADMIN 권한 규칙만 두고 인증 수단은 정하지 않았다
 * (이 프로젝트는 실제 인증이 범위 밖). 그래서 실제 HTTP로는 관리자 API를 호출할 수 없다 — 기존 E2E는 MockMvc의
 * {@code user("admin")}로 우회한다. 인수 테스트는 실제 포트로 요청하므로, 같은 권한 규칙에 Basic 인증과 테스트용
 * 관리자 계정만 붙여 운영 체인보다 먼저 적용한다. 운영 보안 설정 자체는 기존 관리자 E2E가 검증한다
 * (docs/week4/design.md 3번 섹션).
 *
 * <p>{@code @TestConfiguration}이라 컴포넌트 스캔에 잡히지 않는다 — {@link AcceptanceTest}가 명시적으로 가져올 때만 쓴다.
 */
@TestConfiguration
class AcceptanceTestSecurityConfig {

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "admin";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain acceptanceAdminBoundary(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .httpBasic(Customizer.withDefaults())
            // Basic 인증을 쓰는 무상태 API 호출이라 CSRF 토큰을 요구하지 않는다
            .csrf(AbstractHttpConfigurer::disable)
            .build();
    }

    @Bean
    InMemoryUserDetailsManager acceptanceAdminUsers() {
        return new InMemoryUserDetailsManager(
            User.withUsername(ADMIN_USERNAME).password("{noop}" + ADMIN_PASSWORD).roles("ADMIN").build()
        );
    }
}
