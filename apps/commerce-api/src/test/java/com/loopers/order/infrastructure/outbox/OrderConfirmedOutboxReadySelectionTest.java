package com.loopers.order.infrastructure.outbox;

import com.loopers.CommerceApiApplication;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = CommerceApiApplication.class,
    properties = "outbox.relay.enabled=false"
)
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig.class)
@Transactional
class OrderConfirmedOutboxReadySelectionTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private OrderConfirmedOutboxRepository repository;

    @Autowired
    private Environment environment;


    @Test
    @DisplayName("재시도 시각이 지난 이벤트를 오래된 순서로 설정된 최대 건수만큼 조회한다")
    void ordersEligibleRowsByCreationTimeAndLimitsResultToConfiguredBatchSize() {
        int batchSize = batchSize();
        List<OrderConfirmedOutbox> eligible = IntStream.rangeClosed(1, batchSize + 1)
            .mapToObj(index -> outbox(
                "eligible-" + index,
                index % 2 == 0 ? OrderConfirmedOutboxStatus.PENDING : OrderConfirmedOutboxStatus.FAILED,
                NOW.minusSeconds(batchSize + 2L - index),
                CREATED_AT.minusSeconds(index)
            ))
            .toList();
        List<OrderConfirmedOutbox> insertionOrder = new ArrayList<>(eligible);
        Collections.shuffle(insertionOrder, new Random(21));
        insertionOrder.add(outbox("blocked", OrderConfirmedOutboxStatus.BLOCKED, NOW.minusSeconds(100)));
        insertionOrder.add(outbox("published", OrderConfirmedOutboxStatus.PUBLISHED, NOW.minusSeconds(101)));
        insertionOrder.add(outbox("pending-future", OrderConfirmedOutboxStatus.PENDING, NOW.plusMillis(1)));
        insertionOrder.add(outbox("failed-future", OrderConfirmedOutboxStatus.FAILED, NOW.plusMillis(1)));
        repository.saveAllAndFlush(insertionOrder);

        List<OrderConfirmedOutbox> ready = repository.findReadyForRelay(NOW, batchSize);
        List<OrderConfirmedOutbox> expected = eligible.stream()
            .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
            .limit(batchSize)
            .toList();

        assertThat(ready).containsExactlyElementsOf(expected);
        assertThat(ready).hasSize(batchSize);
    }

    private int batchSize() {
        return environment.getRequiredProperty("outbox.relay.batch-size", Integer.class);
    }

    private OrderConfirmedOutbox outbox(
        String eventId,
        OrderConfirmedOutboxStatus status,
        Instant nextAttemptAt
    ) {
        return outbox(eventId, status, nextAttemptAt, CREATED_AT);
    }

    private OrderConfirmedOutbox outbox(
        String eventId,
        OrderConfirmedOutboxStatus status,
        Instant nextAttemptAt,
        Instant createdAt
    ) {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(
            eventId,
            1L,
            "OrderConfirmed",
            1,
            "order-" + eventId,
            "payload-" + eventId,
            createdAt
        );
        outbox.setStatus(status);
        outbox.setNextAttemptAt(nextAttemptAt);
        return outbox;
    }
}
