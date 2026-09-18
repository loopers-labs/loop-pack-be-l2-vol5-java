package com.loopers.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class AdminBoundaryConfig {

    @Bean
    public SecurityFilterChain adminBoundaryFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api-admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            // 고객 API 는 X-USER-ID 헤더로 식별하는 stateless 경로라 CSRF 보호 대상이 아니다.
            // 쿠키 인증을 쓰는 관리자 경로(/api-admin/**)의 CSRF 는 그대로 유지한다.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .build();
    }
}
