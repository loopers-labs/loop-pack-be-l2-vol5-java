package com.loopers.support.w6;

import com.loopers.application.order.OrderConfirmedOutboxFactory;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

/**
 * 보존한 W6 확정·발행 경로 (revision 6f7aac1). W7 제품 코드에는 없다. W6 누락 상태를 새 격리 실행에서 다시 만들 때만 import 한다.
 * 결과 반영을 W6 의 applyResult(확정 + 이벤트 등록, Outbox 없음)로 바꾸고, commit 뒤 W6 publisher 로 직접 보낸다.
 * 이 구성을 쓴 context 는 Outbox 행을 만들지 않는다. @Primary 라 기존 주문 API·OrderFacade·만료 job·조회 scheduler 가 이 결과 반영을 부른다.
 */
@TestConfiguration
public class W6DirectPublicationConfig {

    @Bean
    @Primary
    W6OrderPaymentProcessor w6OrderPaymentProcessor(UserService userService, OrderService orderService, ProductService productService,
                                                    OrderConfirmedOutboxFactory outboxFactory, ApplicationEventPublisher eventPublisher,
                                                    Clock clock) {
        return new W6OrderPaymentProcessor(userService, orderService, productService, outboxFactory, eventPublisher, clock);
    }

    @Bean
    W6KafkaOrderConfirmedPublisher w6KafkaOrderConfirmedPublisher(KafkaTemplate<Object, Object> kafkaTemplate, MeterRegistry meterRegistry,
                                                                  @Value("${kafka.topics.order-events.name}") String topic) {
        return new W6KafkaOrderConfirmedPublisher(kafkaTemplate, meterRegistry, topic);
    }

    @Bean
    W6OrderConfirmedEventListener w6OrderConfirmedEventListener(W6OrderConfirmedPublisher orderConfirmedPublisher) {
        return new W6OrderConfirmedEventListener(orderConfirmedPublisher);
    }
}
