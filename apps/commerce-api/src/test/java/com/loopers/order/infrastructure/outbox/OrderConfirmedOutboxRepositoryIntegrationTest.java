package com.loopers.order.infrastructure.outbox;

import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
@DisplayName("OrderConfirmed Outbox 재시도 대상 조회")
class OrderConfirmedOutboxRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final int BATCH_SIZE = 10;

    @Autowired
    private OrderConfirmedOutboxRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("시도 시각이 된 PENDING과 FAILED 행을 재시도 대상으로 조회한다")
    void findsDuePendingAndFailedOutboxes() {
        OrderConfirmedOutbox pending = outbox("pending");
        pending.setNextAttemptAt(NOW);

        OrderConfirmedOutbox failed = outbox("failed");
        failed.setStatus(OrderConfirmedOutboxStatus.FAILED);
        failed.setNextAttemptAt(NOW);

        repository.saveAll(List.of(pending, failed));
        entityManager.flush();
        entityManager.clear();

        List<OrderConfirmedOutbox> result = repository.findReadyForRelay(NOW, BATCH_SIZE);

        assertThat(result)
            .extracting(OrderConfirmedOutbox::getEventId)
            .containsExactlyInAnyOrder(pending.getEventId(), failed.getEventId());
    }

    @Test
    @DisplayName("시도 시각 전인 행과 BLOCKED·PUBLISHED 행은 재시도 대상으로 조회하지 않는다")
    void excludesNotYetDueAndTerminalOutboxes() {
        OrderConfirmedOutbox pendingNotDue = outbox("pending-not-due");
        pendingNotDue.setNextAttemptAt(NOW.plusSeconds(1));

        OrderConfirmedOutbox failedNotDue = outbox("failed-not-due");
        failedNotDue.setStatus(OrderConfirmedOutboxStatus.FAILED);
        failedNotDue.setNextAttemptAt(NOW.plusSeconds(1));

        OrderConfirmedOutbox blocked = outbox("blocked");
        blocked.setStatus(OrderConfirmedOutboxStatus.BLOCKED);
        blocked.setNextAttemptAt(NOW);

        OrderConfirmedOutbox published = outbox("published");
        published.setStatus(OrderConfirmedOutboxStatus.PUBLISHED);
        published.setNextAttemptAt(NOW);

        repository.saveAll(List.of(pendingNotDue, failedNotDue, blocked, published));
        entityManager.flush();
        entityManager.clear();

        List<OrderConfirmedOutbox> result = repository.findReadyForRelay(NOW, BATCH_SIZE);

        assertThat(result).isEmpty();
    }

    private OrderConfirmedOutbox outbox(String suffix) {
        String eventId = "order-confirmed:" + suffix + ":" + UUID.randomUUID();
        return OrderConfirmedOutbox.pending(
            eventId,
            42L,
            "OrderConfirmed",
            1,
            "42",
            "{\"eventId\":\"" + eventId + "\"}",
            NOW
        );
    }
}
