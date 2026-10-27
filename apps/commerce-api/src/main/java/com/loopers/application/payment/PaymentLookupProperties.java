package com.loopers.application.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 스케줄러 조회 설정 (설계 5-3 결정 ⑫). 값은 application.yml 의 payment.lookup 에서 바인딩된다.
 * enabled 가 false 여도 스케줄러 빈은 만들어지고 @Scheduled 자동 실행만 꺼진다. 테스트는 Facade 메서드를 직접 호출한다.
 */
@ConfigurationProperties(value = "payment.lookup")
public record PaymentLookupProperties(
    boolean enabled,
    Duration fixedDelay,
    Duration minAge,
    Duration initialInterval,
    int multiplier,
    Duration maxInterval,
    int maxLookups
) { }
