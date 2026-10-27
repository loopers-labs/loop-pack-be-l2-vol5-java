package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 실제 HTTP adapter 설정 (설계 1-6 결정 ㉒·㉓·㉔, 5-7 결정 ㉜). 값은 application.yml 의 payment.pg 에서 바인딩된다.
 * base URL·callback URL·제공자 계정(X-USER-ID)은 설정에 고정하고 고객 입력으로 바꾸지 않는다.
 * enabled 가 false 면 adapter 는 어떤 호출이든 즉시 예외를 던진다 (test 프로필 기본값). 가짜 PG 를 올리지 않은 테스트가 실제 HTTP 를 보내 조용히 UNKNOWN 으로 끝나는 것을 막는다.
 * 두 timeout 은 0(무한 대기)을 두지 않는다. circuitBreaker 는 조회(목록·단건) 한 경로에만 적용되는 서킷 설정이다. 키 이름은 Resilience4j 설정 이름을 따른다.
 */
@ConfigurationProperties(value = "payment.pg")
public record PaymentGatewayProperties(
    boolean enabled,
    String baseUrl,
    String callbackUrl,
    String userId,
    Duration connectTimeout,
    Duration readTimeout,
    CircuitBreaker circuitBreaker
) {

    /**
     * slidingWindowType 은 COUNT_BASED(최근 N회 호출) 또는 TIME_BASED. 조회는 호출이 드물어 COUNT_BASED 를 기본으로 둔다.
     * failureRateThreshold 는 백분율(50 = 50%). 표본이 minimumNumberOfCalls 이상 모였을 때부터 판정한다.
     */
    public record CircuitBreaker(
        String slidingWindowType,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState
    ) { }
}