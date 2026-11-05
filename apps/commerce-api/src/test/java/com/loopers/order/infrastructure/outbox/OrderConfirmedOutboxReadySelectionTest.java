package com.loopers.order.infrastructure.outbox;

import com.loopers.CommerceApiApplication;
import com.loopers.order.application.outbox.OutboxPublisher;
import com.loopers.order.application.outbox.PublishResult;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("폴링마다 현재 DB에서 오래된 처리 가능 이벤트를 배치 크기만큼 순차 발행한다")
    void publishesOldestCurrentlyEligibleRowsAcrossPolls() throws Exception {
        String prefix = "poll-sequence-" + UUID.randomUUID();
        String cEventId = prefix + "-C";
        String aEventId = prefix + "-A";
        String bEventId = prefix + "-B";
        String dEventId = prefix + "-D";
        List<String> eventIds = List.of(cEventId, aEventId, bEventId, dEventId);

        CountDownLatch cInserted = new CountDownLatch(1);
        CountDownLatch allowCCommit = new CountDownLatch(1);
        AtomicReference<Long> cDatabaseId = new AtomicReference<>();
        OrderConfirmedOutbox c = outbox(
            cEventId,
            OrderConfirmedOutboxStatus.PENDING,
            NOW.minusMillis(1),
            CREATED_AT.minusSeconds(60)
        );

        OutboxPublisher publisher = mock(OutboxPublisher.class);
        when(publisher.publish(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.SUCCESS));
        int batchSize = 2;
        MutableClock clock = new MutableClock(NOW);
        Duration retryInterval = DurationStyle.detectAndParse(
            environment.getRequiredProperty("outbox.relay.poll-interval")
        );
        OrderConfirmedOutboxRelay relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            clock,
            retryInterval,
            batchSize,
            environment.getRequiredProperty("outbox.relay.max-attempts", Integer.class)
        );
        OrderConfirmedOutbox a = outbox(
            aEventId, OrderConfirmedOutboxStatus.PENDING, NOW.minusMillis(1), CREATED_AT.minusSeconds(50)
        );
        OrderConfirmedOutbox b = outbox(
            bEventId, OrderConfirmedOutboxStatus.PENDING, NOW.minusMillis(1), CREATED_AT.minusSeconds(40)
        );
        OrderConfirmedOutbox d = outbox(
            dEventId, OrderConfirmedOutboxStatus.FAILED, NOW.plusSeconds(5), CREATED_AT.minusSeconds(30)
        );

        CompletableFuture<Void> cTransaction = null;
        try {
            cTransaction = insertAndHoldTransaction(c, cDatabaseId, cInserted, allowCCommit);
            // C가 가장 낮은 ID를 받지만 트랜잭션 커밋 전까지는 조회되지 않는다.
            assertThat(cInserted.await(5, TimeUnit.SECONDS)).isTrue();
            repository.saveAllAndFlush(List.of(a, b, d));
            assertThat(cDatabaseId.get()).isLessThan(a.getId());
            assertThat(repository.findByEventId(cEventId)).isEmpty();

            // 첫 폴링은 A와 B를 처리한다. C는 미커밋이고 D의 재시도 시각은 아직 오지 않았다.
            relay.pollOnce();

            assertStatus(aEventId, OrderConfirmedOutboxStatus.PUBLISHED);
            assertStatus(bEventId, OrderConfirmedOutboxStatus.PUBLISHED);
            assertStatus(dEventId, OrderConfirmedOutboxStatus.FAILED);

            // C가 커밋된 뒤 같은 Relay가 다음 폴링에서 C를 찾아 발행해야 한다.
            allowCCommit.countDown();
            cTransaction.get(5, TimeUnit.SECONDS);
            assertStatus(cEventId, OrderConfirmedOutboxStatus.PENDING);

            relay.pollOnce();

            assertStatus(cEventId, OrderConfirmedOutboxStatus.PUBLISHED);
            assertStatus(dEventId, OrderConfirmedOutboxStatus.FAILED);

            // 같은 Relay의 시각을 D의 재시도 가능 시각까지 진행한다.
            clock.setInstant(NOW.plusSeconds(5));
            relay.pollOnce();

            assertStatus(dEventId, OrderConfirmedOutboxStatus.PUBLISHED);

            InOrder publishOrder = inOrder(publisher);
            publishOrder.verify(publisher).publish("order-" + aEventId, "payload-" + aEventId);
            publishOrder.verify(publisher).publish("order-" + bEventId, "payload-" + bEventId);
            publishOrder.verify(publisher).publish("order-" + cEventId, "payload-" + cEventId);
            publishOrder.verify(publisher).publish("order-" + dEventId, "payload-" + dEventId);
            verifyNoMoreInteractions(publisher);
        } finally {
            // C 트랜잭션을 해제하고 종료를 기다린 뒤 테스트 행을 정리한다.
            allowCCommit.countDown();
            try {
                if (cTransaction != null) {
                    cTransaction.get(5, TimeUnit.SECONDS);
                }
            } finally {
                eventIds.forEach(eventId ->
                    repository.findByEventId(eventId).ifPresent(repository::delete)
                );
            }
        }

        eventIds.forEach(eventId -> assertThat(repository.findByEventId(eventId)).isEmpty());
    }

    private CompletableFuture<Void> insertAndHoldTransaction(
        OrderConfirmedOutbox outbox,
        AtomicReference<Long> generatedId,
        CountDownLatch inserted,
        CountDownLatch allowCommit
    ) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            OrderConfirmedOutbox saved = repository.saveAndFlush(outbox);
            generatedId.set(saved.getId());
            inserted.countDown();
            try {
                if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to commit " + outbox.getEventId());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }));
    }

    private void assertStatus(String eventId, OrderConfirmedOutboxStatus expectedStatus) {
        assertThat(repository.findByEventId(eventId).orElseThrow().getStatus()).isEqualTo(expectedStatus);
    }

    private static class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(new AtomicReference<>(instant), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void setInstant(Instant instant) {
            this.instant.set(instant);
        }
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
