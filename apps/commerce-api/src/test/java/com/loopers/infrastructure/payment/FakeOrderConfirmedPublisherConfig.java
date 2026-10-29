package com.loopers.infrastructure.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 가짜 발행기를 필요한 테스트에만 올린다. 컴포넌트 스캔에 잡히지 않으므로 @Import 로만 등록된다.
 * 운영 빈(KafkaOrderConfirmedPublisher)과 함께 있을 때 @Primary 로 가짜가 이긴다. FakePaymentGatewayConfig 와 같은 방식이다.
 */
@TestConfiguration
public class FakeOrderConfirmedPublisherConfig {

    @Primary
    @Bean
    public FakeOrderConfirmedPublisher fakeOrderConfirmedPublisher() {
        return new FakeOrderConfirmedPublisher();
    }
}
