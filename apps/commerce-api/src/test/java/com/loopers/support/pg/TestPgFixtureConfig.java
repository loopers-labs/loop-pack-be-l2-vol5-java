package com.loopers.support.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGatewayRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 모든 통합 테스트 컨텍스트에서 PG 호출이 테스트마다 띄운 TestPgServer로 가게 한다. 운영 레지스트리 구성 검증은
// PaymentGatewayConfigTest가 따로 한다.
@Configuration
public class TestPgFixtureConfig {
    @Bean
    TestPgFixture testPgFixture(ObjectMapper objectMapper) {
        return new TestPgFixture(objectMapper);
    }

    @Bean
    @Primary
    PaymentGatewayRegistry testPaymentGatewayRegistry(TestPgFixture testPgFixture) {
        return new PaymentGatewayRegistry(List.of(testPgFixture.gateway()), List.of(TestPgFixture.PROVIDER_ID));
    }
}
