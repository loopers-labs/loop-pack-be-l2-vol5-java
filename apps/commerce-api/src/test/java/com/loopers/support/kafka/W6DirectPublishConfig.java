package com.loopers.support.kafka;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.order.OrderEventSerializer;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxRepository;
import com.loopers.domain.order.OrderOutboxService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * W6의 "commit 뒤 직접 발행" 경로를 테스트 context 안에서 다시 세우는 구성 (W7 design.md 5절 결정 C).
 *
 * <p>W7부터 제품 코드는 확정 트랜잭션에서 Outbox를 기록하고 직접 발행하지 않는다. 그래서 W6 누락 구간의 기준선
 * ({@code OrderConfirmedOmissionReproTest})은 이 구성을 import해, Outbox 기록 자리를 W6처럼 "commit 뒤 발행기 호출"로 바꿔 실행한다.
 * Outbox 행은 쓰지 않는다. 발행기는 {@link RecordingOrderEventPublisher}라서 W6 테스트의 send 직전 hook·실패 주입·실제 broker 위임을 그대로 쓴다.
 *
 * <p>제품의 운영 경로를 끄는 스위치가 아니다. 이 구성을 import한 테스트 context에서만 쓰인다(@TestConfiguration은 component scan에서 빠진다).
 */
@TestConfiguration
public class W6DirectPublishConfig {

    @Bean
    @Primary
    OrderOutboxService w6DirectPublishingOutboxService(
        OrderOutboxRepository orderOutboxRepository,
        OrderEventSerializer orderEventSerializer,
        OrderEventPublisher orderEventPublisher
    ) {
        return new DirectPublishingOutboxService(orderOutboxRepository, orderEventSerializer, orderEventPublisher);
    }

    /** Outbox에 저장하는 대신, 확정 트랜잭션이 commit된 뒤에 W6처럼 발행기를 한 번 부른다. */
    static class DirectPublishingOutboxService extends OrderOutboxService {

        private final OrderEventPublisher orderEventPublisher;

        DirectPublishingOutboxService(
            OrderOutboxRepository orderOutboxRepository,
            OrderEventSerializer orderEventSerializer,
            OrderEventPublisher orderEventPublisher
        ) {
            super(orderOutboxRepository, orderEventSerializer);
            this.orderEventPublisher = orderEventPublisher;
        }

        @Override
        public OrderOutboxModel append(OrderConfirmedEvent event, Instant recordedAt) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orderEventPublisher.publish(event);
                }
            });
            return null;
        }
    }
}
