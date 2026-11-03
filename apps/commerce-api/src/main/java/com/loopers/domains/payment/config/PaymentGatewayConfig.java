package com.loopers.domains.payment.config;

import com.loopers.domains.payment.app.PaymentGatewayRegistry;
import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.out.PaymentCircuitBreakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 어댑터가 하나도 없을 때도 레지스트리가 시작 실패 이유를 알리도록 List 주입 대신 ObjectProvider로 모은다.
@Configuration
@EnableConfigurationProperties(PaymentProvidersProperties.class)
public class PaymentGatewayConfig {
    @Bean
    PaymentGatewayRegistry paymentGatewayRegistry(ObjectProvider<PaymentGateway> gateways,
        PaymentProvidersProperties properties, PaymentCircuitBreakers paymentCircuitBreakers) {
        return new PaymentGatewayRegistry(
            gateways.orderedStream().map(paymentCircuitBreakers::wrap).toList(), properties.priority());
    }
}
