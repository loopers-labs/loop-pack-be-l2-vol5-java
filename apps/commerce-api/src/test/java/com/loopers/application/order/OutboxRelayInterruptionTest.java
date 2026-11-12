package com.loopers.application.order;

import com.loopers.CommerceApiApplication;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OutboxStatus;
import com.loopers.infrastructure.order.OrderConfirmedOutboxJpaRepository;
import com.loopers.test.kafka.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(KafkaTestContainersConfig.class)
class OutboxRelayInterruptionTest {

    @Autowired OrderConfirmedOutboxRepository outboxRepository;
    @Autowired OrderConfirmedOutboxJpaRepository outboxJpaRepository;
    @Autowired OutboxPublisher outboxPublisher;

    @SpyBean OrderConfirmedOutboxRelay spyRelay;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("commit 후 relay 실행 전 중단: Outbox PENDING 유지, 재시작 후 발행 완료")
    @Test
    void pendingOutbox_publishedAfterRelayRestart() throws Exception {
        String eventId = "order-confirmed:v1:1001";
        outboxRepository.save(OrderConfirmedOutbox.create(eventId, 1001L, "1001", minimalPayload(eventId, 1001L)));

        assertThat(outboxJpaRepository.findByEventId(eventId).get().getStatus())
            .isEqualTo(OutboxStatus.PENDING);

        // relay 최초 실행 (재시작 시뮬레이션)
        spyRelay.relay();

        assertThat(outboxJpaRepository.findByEventId(eventId).get().getStatus())
            .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @DisplayName("broker 확인 후 PUBLISHED 저장 전 중단: Outbox PENDING 유지, relay 재시작 후 재발행·PUBLISHED")
    @Test
    void brokerConfirmedButPublishedNotSaved_republishesOnRestart() {
        String eventId = "order-confirmed:v1:1002";
        outboxRepository.save(OrderConfirmedOutbox.create(eventId, 1002L, "1002", minimalPayload(eventId, 1002L)));

        // broker 확인 성공 후 PUBLISHED 저장 직전 중단 시뮬레이션
        doThrow(new RuntimeException("PUBLISHED 저장 실패 시뮬레이션"))
            .when(spyRelay).markPublished(any(), any());

        try {
            spyRelay.process(outboxJpaRepository.findByEventId(eventId).get());
        } catch (Exception ignored) {
        }

        // Outbox 여전히 PENDING
        assertThat(outboxJpaRepository.findByEventId(eventId).get().getStatus())
            .isEqualTo(OutboxStatus.PENDING);

        // relay 재시작: 새 릴레이 인스턴스로 같은 DB+Kafka 사용
        OrderConfirmedOutboxRelay newRelay = new OrderConfirmedOutboxRelay(outboxRepository, outboxPublisher);
        newRelay.process(outboxJpaRepository.findByEventId(eventId).get());

        assertThat(outboxJpaRepository.findByEventId(eventId).get().getStatus())
            .isEqualTo(OutboxStatus.PUBLISHED);
    }

    private String minimalPayload(String eventId, long orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
            + ",\"confirmedAt\":\"2026-11-06T00:00:00+09:00\",\"items\":[]}";
    }
}
