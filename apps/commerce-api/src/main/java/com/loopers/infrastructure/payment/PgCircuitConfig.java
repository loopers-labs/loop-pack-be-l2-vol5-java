package com.loopers.infrastructure.payment;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 회로가 무엇을 실패로 세는지 정한다(설계 2.8). 수치는 application.yml 의 pgBase 를 그대로 쓰고 여기서 다시 선언하지 않는다.
 * <pre>
 * 구분                 pgCreate(생성)                          pgLookup(조회)
 * 실패로 셈(예외)       PG 통신 실패·계약 외 상태·해석 불가        PG 통신 실패·계약 외 상태·해석 불가
 * 실패로 셈(정상 응답)   응답의 주문·금액이 요청과 다름             요청한 거래 키·주문이 아닌 거래가 옴
 * 성공으로 셈           그 밖의 응답(정확한 422 무효과 거절 포함)  그 밖의 응답(404 거래 없음·빈 목록·정확한 복수 거래·내부 비교 충돌 포함)
 * 표본에서 뺌           우리 풀 대기 실패, 식별한 프로그램 오류     우리 풀 대기 실패, 식별한 프로그램 오류
 * </pre>
 * 표본에서 빼는 것은 ignore predicate 로만 한다. recordException=false 로 흉내 내면 성공으로 기록되기 때문이다.
 */
@Configuration
public class PgCircuitConfig {

    static final String PG_CREATE = "pgCreate";
    static final String PG_LOOKUP = "pgLookup";

    @Bean
    public CircuitBreakerConfigCustomizer pgCreateCircuitCustomizer() {
        return CircuitBreakerConfigCustomizer.of(PG_CREATE, builder -> builder
            .recordResult(PgCircuitConfig::isDirectCreateResponseMismatch)
            .ignoreException(PgCircuitConfig::isOurSideError));
    }

    @Bean
    public CircuitBreakerConfigCustomizer pgLookupCircuitCustomizer() {
        return CircuitBreakerConfigCustomizer.of(PG_LOOKUP, builder -> builder
            .recordResult(PgCircuitConfig::isDirectLookupTargetMismatch)
            .ignoreException(PgCircuitConfig::isOurSideError));
    }

    static boolean isDirectCreateResponseMismatch(Object result) {
        return result instanceof PgCreateResult create && create.directCreateResponseMismatch();
    }

    static boolean isDirectLookupTargetMismatch(Object result) {
        return result instanceof PgLookupResult lookup && lookup.directLookupTargetMismatch();
    }

    /** PG 상태가 아니라 우리 쪽 원인이라 회로 판단에 넣지 않는다(생성 본문 안의 예상 밖 회로 거절도 프로그램 오류로 감싸 여기 포함). */
    static boolean isOurSideError(Throwable error) {
        return error instanceof PgPoolAcquireException || error instanceof PgAdapterProgrammingException;
    }
}
