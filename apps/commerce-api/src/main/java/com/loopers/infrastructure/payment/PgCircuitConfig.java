package com.loopers.infrastructure.payment;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 회로의 결과·예외 판정(설계 2.8). 수치는 application.yml 의 pgBase 를 그대로 쓰고 여기서 다시 선언하지 않는다.
 * 생성과 조회의 분류는 각자의 결과 타입으로 따로 정의하고 서로 복사하지 않는다.
 */
@Configuration
public class PgCircuitConfig {

    static final String PG_CREATE = "pgCreate";
    static final String PG_LOOKUP = "pgLookup";

    /**
     * 생성 응답의 주문·금액이 요청과 다른 직접 위반만 결과 실패로 센다. 정확한 422 무효과 거절은 성공이다.
     * 풀 대기 실패와 식별한 프로그램 오류(본문 안의 예상 밖 CallNotPermittedException 포함)는 표본에서 제외한다.
     */
    @Bean
    public CircuitBreakerConfigCustomizer pgCreateCircuitCustomizer() {
        return CircuitBreakerConfigCustomizer.of(PG_CREATE, builder -> builder
            .recordResult(PgCircuitConfig::isDirectCreateResponseMismatch)
            .ignoreException(PgCircuitConfig::isExcludedCreateError));
    }

    /**
     * 직접 조회 대상 위반만 결과 실패로 센다(빈 목록·정확한 복수 거래·내부 비교 충돌은 성공).
     * 우리 풀 대기 실패와 식별한 프로그램 오류는 표본에서 제외한다. recordException=false 로 흉내 내면 성공으로 기록되므로
     * ignore predicate 로만 제외한다.
     */
    @Bean
    public CircuitBreakerConfigCustomizer pgLookupCircuitCustomizer() {
        return CircuitBreakerConfigCustomizer.of(PG_LOOKUP, builder -> builder
            .recordResult(PgCircuitConfig::isDirectLookupTargetMismatch)
            .ignoreException(PgCircuitConfig::isExcludedLookupError));
    }

    static boolean isDirectLookupTargetMismatch(Object result) {
        return result instanceof PgLookupResult lookup && lookup.directLookupTargetMismatch();
    }

    static boolean isDirectCreateResponseMismatch(Object result) {
        return result instanceof PgCreateResult create && create.directCreateResponseMismatch();
    }

    static boolean isExcludedCreateError(Throwable error) {
        return error instanceof PgPoolAcquireException || error instanceof PgAdapterProgrammingException;
    }

    static boolean isExcludedLookupError(Throwable error) {
        return error instanceof PgPoolAcquireException || error instanceof PgAdapterProgrammingException;
    }
}
