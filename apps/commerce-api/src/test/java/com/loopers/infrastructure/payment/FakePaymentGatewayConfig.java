package com.loopers.infrastructure.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 가짜 PG 를 필요한 테스트에만 올린다. 컴포넌트 스캔에 잡히지 않으므로 @Import 로만 등록된다.
 * 운영 빈(HttpPaymentGateway, test 프로필에서는 enabled=false 라 호출 시 예외)과 함께 있을 때 @Primary 로 가짜가 이긴다.
 */
@TestConfiguration
public class FakePaymentGatewayConfig {

    @Primary
    @Bean
    public FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}