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
import java.util.List;
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
    @DisplayName("재시도 시각이 도래한 PENDING·FAILED 이벤트만 조회한다")
    void selectsOnlyDuePendingAndFailedRows() {
        OrderConfirmedOutbox pendingBeforeNow = outbox(
            "pending-before-now", OrderConfirmedOutboxStatus.PENDING, NOW.minusMillis(1)
        );
        OrderConfirmedOutbox failedAtNow = outbox(
            "failed-at-now", OrderConfirmedOutboxStatus.FAILED, NOW
        );
        OrderConfirmedOutbox pendingAfterNow = outbox(
            "pending-after-now", OrderConfirmedOutboxStatus.PENDING, NOW.plusMillis(1)
        );
        OrderConfirmedOutbox failedAfterNow = outbox(
            "failed-after-now", OrderConfirmedOutboxStatus.FAILED, NOW.plusMillis(1)
        );
        OrderConfirmedOutbox blockedBeforeNow = outbox(
            "blocked-before-now", OrderConfirmedOutboxStatus.BLOCKED, NOW.minusMillis(1)
        );
        OrderConfirmedOutbox publishedBeforeNow = outbox(
            "published-before-now", OrderConfirmedOutboxStatus.PUBLISHED, NOW.minusMillis(1)
        );
        repository.saveAllAndFlush(List.of(
            pendingAfterNow,
            blockedBeforeNow,
            failedAtNow,
            publishedBeforeNow,
            failedAfterNow,
            pendingBeforeNow
        ));

        List<OrderConfirmedOutbox> ready = repository.findReadyForRelay(NOW, batchSize());

        assertThat(ready).containsExactlyInAnyOrder(pendingBeforeNow, failedAtNow);
    }

    @Test
    @DisplayName("조회 결과는 생성 시각이 오래된 순서로 정렬되어 있어야 한다")
    void ordersEligibleRowsByOldestCreationTime() {
        OrderConfirmedOutbox oldest = outbox(
            "oldest", OrderConfirmedOutboxStatus.PENDING,
            NOW.minusSeconds(1), CREATED_AT.minusSeconds(30)
        );
        OrderConfirmedOutbox middle = outbox(
            "middle", OrderConfirmedOutboxStatus.FAILED,
            NOW.minusSeconds(3), CREATED_AT.minusSeconds(20)
        );
        OrderConfirmedOutbox newest = outbox(
            "newest", OrderConfirmedOutboxStatus.PENDING,
            NOW.minusSeconds(5), CREATED_AT.minusSeconds(10)
        );
        repository.saveAllAndFlush(List.of(newest, oldest, middle));

        List<OrderConfirmedOutbox> ready = repository.findReadyForRelay(NOW, batchSize());

        assertThat(ready).containsExactly(oldest, middle, newest);
    }

    @Test
    @DisplayName("조회 결과는 application-test.yml의 batch-size를 넘지 않는다")
    void limitsResultToConfiguredBatchSize() {
        int batchSize = batchSize();
        List<OrderConfirmedOutbox> eligible = IntStream.range(0, batchSize + 2)
            .mapToObj(index -> outbox(
                "eligible-" + index,
                index % 2 == 0 ? OrderConfirmedOutboxStatus.PENDING : OrderConfirmedOutboxStatus.FAILED,
                NOW.minusMillis(1)
            ))
            .toList();
        repository.saveAllAndFlush(eligible);

        List<OrderConfirmedOutbox> ready = repository.findReadyForRelay(NOW, batchSize);

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
