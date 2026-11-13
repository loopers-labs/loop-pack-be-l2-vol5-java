package com.loopers.config;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.ErrorType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * 관리자와 고객 API 의 보안 체인을 한곳에서 정한다. 두 체인의 경로는 겹치지 않는다.
 *
 * <p>로컬 실습용 경계이며 로그인 기능은 없다. 이 빈들이 있으면 Spring Boot 의 기본 보안 체인은 만들어지지 않는다.
 * 필터 단계의 거절은 {@code ApiControllerAdvice} 가 처리하지 못하므로 {@link SecurityErrorResponseWriter} 로
 * 같은 {@code ApiResponse} 형식을 직접 쓴다.
 */
@Configuration
public class SecurityConfig {

    /**
     * {@code X-USER-ID} 로 요청자를 식별해야 하는 고객 API. 자신의 데이터를 다루는 API 를 추가하면 여기에 등록한다.
     * 등록하지 않으면 식별 없이 열리고 Controller 에 요청자가 {@code null} 로 들어가므로, 그런 API 마다
     * "헤더 누락 400" E2E 테스트를 둔다.
     */
    static final RequestMatcher IDENTIFIED_PATHS = new OrRequestMatcher(
        antMatcher("/api/v1/points/**"),
        antMatcher("/api/v1/products/*/likes"),
        antMatcher("/api/v1/users/*/likes"),
        antMatcher("/api/v1/orders/**"),
        antMatcher("/api/v1/coupon-campaigns/*/issues"),
        antMatcher("/api/v1/coupons/**")
    );

    /** 관리자 API. 관리자 역할만 허용하며 미식별·권한 부족 모두 403 이다. */
    @Bean
    @Order(1)
    SecurityFilterChain adminChain(
        HttpSecurity http, SecurityErrorResponseWriter errorWriter, Environment environment
    ) throws Exception {
        // local 에서만 관리자 API 를 직접 부를 수 있게 Basic 인증을 켠다(부하 실험의 캠페인 준비·열기). 다른 프로필에는 인증 수단이 없다.
        // Basic 은 쿠키 세션을 쓰지 않으므로 이때 CSRF 를 끈다.
        if (environment.acceptsProfiles(Profiles.of(LocalAdminConfig.PROFILE))) {
            http.httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) ->
                    errorWriter.write(response, ErrorType.FORBIDDEN, null)))
                .csrf(AbstractHttpConfigurer::disable);
        }
        return http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().hasRole("ADMIN"))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    errorWriter.write(response, ErrorType.FORBIDDEN, null))
                .accessDeniedHandler((request, response, exception) ->
                    errorWriter.write(response, ErrorType.FORBIDDEN, null)))
            .build();
    }

    /**
     * 고객 API. 식별이 필요한 경로는 {@link CustomerIdentityFilter} 가 요청자를 확인하고, 나머지(카탈로그 등)는 열어 둔다.
     * 헤더로 식별해 쿠키 세션이 없으므로 CSRF 를 끄고 세션을 만들지 않는다.
     */
    @Bean
    @Order(2)
    SecurityFilterChain customerChain(
        HttpSecurity http, UserService userService, SecurityErrorResponseWriter errorWriter
    ) throws Exception {
        return http.securityMatcher("/api/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(
                new CustomerIdentityFilter(IDENTIFIED_PATHS, userService, errorWriter),
                AnonymousAuthenticationFilter.class
            )
            // 필터가 이미 거절하므로 안전망이다. 여기서 막힌다면 필터와 규칙이 어긋난 것이다.
            .authorizeHttpRequests(rules -> rules
                .requestMatchers(IDENTIFIED_PATHS).authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    errorWriter.write(response, ErrorType.FORBIDDEN, null)))
            .build();
    }
}
