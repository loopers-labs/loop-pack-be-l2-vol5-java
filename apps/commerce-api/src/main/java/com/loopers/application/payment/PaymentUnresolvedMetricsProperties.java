package com.loopers.application.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 미종결 결제 지표 집계 설정 (10주차).
 *
 * @param enabled    스케줄 집계 활성화. 테스트는 꺼 두고 필요한 테스트가 직접 refresh 를 호출한다.
 * @param fixedDelay 집계 간격. fixedRate 가 아니라 fixedDelay 이므로 집계가 느려져도 작업이 쌓이지 않는다.
 */
@ConfigurationProperties(prefix = "payment.metrics.unresolved")
public record PaymentUnresolvedMetricsProperties(boolean enabled, Duration fixedDelay) {
}
