package com.loopers.domains.payment.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Clock Bean은 결제 쪽과 주문 서비스가 함께 주입받는다(타입 주입이라 import 의존은 없다).
@Configuration
public class PaymentClockConfig {
    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }
}
