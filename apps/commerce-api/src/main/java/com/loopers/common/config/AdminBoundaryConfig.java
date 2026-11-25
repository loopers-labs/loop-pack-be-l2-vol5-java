package com.loopers.common.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(AdminBasicAuthProperties.class)
public class AdminBoundaryConfig {
    private static final Set<String> BASIC_AUTH_FORBIDDEN_PROFILES = Set.of("prd", "qa");

    @Bean
    SecurityFilterChain adminBoundary(HttpSecurity http, AdminBasicAuthProperties basicAuth, Environment environment)
        throws Exception {
        rejectBasicAuthInForbiddenProfiles(basicAuth, environment);
        http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(403)));
        // 계정이 없으면 인증 수단을 아예 붙이지 않아 지금처럼 모든 관리자 요청이 403이다.
        // 틀린 자격 증명은 httpBasic 기본 entry point가 401로 답하고, 헤더 없는 요청은 위 entry point가 403으로 답한다.
        if (basicAuth.enabled() && basicAuth.hasAccount()) {
            http.httpBasic(Customizer.withDefaults())
                .authenticationManager(adminAuthenticationManager(basicAuth));
        }
        return http.build();
    }

    // 운영 계열 프로필에서 환경 변수로 켜지는 실수를 요청 처리 전에 막는다.
    private static void rejectBasicAuthInForbiddenProfiles(AdminBasicAuthProperties basicAuth, Environment environment) {
        if (!basicAuth.enabled()) {
            return;
        }
        List<String> forbidden = Arrays.stream(environment.getActiveProfiles())
            .filter(BASIC_AUTH_FORBIDDEN_PROFILES::contains)
            .toList();
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException(
                "commerce.admin.basic-auth.enabled는 local·dev 전용이라 다음 프로필에서 켤 수 없습니다: " + forbidden);
        }
    }

    // 부모 AuthenticationManager를 두지 않는다. Boot 기본 사용자(생성 비밀번호)로 관리자 인증이 넘어가지 않게 하기 위해서다.
    private static ProviderManager adminAuthenticationManager(AdminBasicAuthProperties basicAuth) {
        PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(User.withUsername(basicAuth.username())
            .password(passwordEncoder.encode(basicAuth.password()))
            .roles("ADMIN")
            .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(users);
        return new ProviderManager(provider);
    }
}
