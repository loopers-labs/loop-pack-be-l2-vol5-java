package com.loopers.infrastructure.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class PaymentProviderConfig {

    @Bean
    TestPgPaymentAdapter paymentProvider(
        @Value("${commerce.payment.pg.base-url}") String baseUrl,
        @Value("${commerce.payment.pg.callback-url}") String callbackUrl,
        @Value("${commerce.payment.pg.connect-timeout}") Duration connectTimeout,
        @Value("${commerce.payment.pg.read-timeout}") Duration readTimeout,
        @Value("${commerce.payment.pg.lookup-read-timeout}") Duration lookupReadTimeout
    ) {
        return new TestPgPaymentAdapter(baseUrl, callbackUrl, connectTimeout, readTimeout, lookupReadTimeout);
    }

    /**
     * 주문 흐름이 쓰는 PG 포트. 조회만 retry와 회로로 감싼다.
     * 연결 풀 대기·연결·응답 대기 설정의 합을 다음 재시도 판단용 추정치로 쓴다. 실제 호출 전체의 최대 시간 보장은 아니다.
     */
    @Bean
    @Primary
    ResilientPaymentProvider resilientPaymentProvider(
        TestPgPaymentAdapter adapter,
        @Value("${commerce.payment.pg.connect-timeout}") Duration connectTimeout,
        @Value("${commerce.payment.pg.lookup-read-timeout}") Duration lookupReadTimeout,
        @Value("${commerce.payment.pg.lookup-budget}") Duration lookupBudget,
        @Value("${commerce.payment.pg.lookup-retry.max-attempts}") int maxAttempts,
        @Value("${commerce.payment.pg.lookup-retry.first-wait}") Duration firstWait,
        @Value("${commerce.payment.pg.lookup-retry.wait-multiplier}") double waitMultiplier,
        @Value("${commerce.payment.pg.lookup-circuit.window-size}") int windowSize,
        @Value("${commerce.payment.pg.lookup-circuit.minimum-calls}") int minimumCalls,
        @Value("${commerce.payment.pg.lookup-circuit.failure-rate-threshold}") float failureRateThreshold,
        @Value("${commerce.payment.pg.lookup-circuit.open-wait}") Duration openWait,
        @Value("${commerce.payment.pg.lookup-circuit.half-open-calls}") int halfOpenCalls
    ) {
        Duration attemptEstimate = connectTimeout.multipliedBy(2).plus(lookupReadTimeout);
        LookupResilienceSettings settings = new LookupResilienceSettings(lookupBudget, attemptEstimate,
            maxAttempts, firstWait, waitMultiplier, windowSize, minimumCalls, failureRateThreshold, openWait, halfOpenCalls);
        return new ResilientPaymentProvider(adapter, settings, Clock.systemUTC());
    }
}
