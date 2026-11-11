package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderItem;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 단일 relay 한 회차의 규칙 (7주차 과제 3~4절 📄 p.3~4, 설계 D22~D29). 실제 MySQL 에 Outbox 행을 두고, 전송기는 결과를 주입하는 가짜로 바꾼다.
 * 실제 broker 로의 전송·결과 분류는 KafkaOutboxMessageSenderTest 가, 실제 consumer 까지 이어지는 흐름은 crossAppTest 가 본다.
 */
@SpringBootTest
@Import(OrderConfirmedOutboxRelayTest.FakeSenderConfig.class)
class OrderConfirmedOutboxRelayTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-07T02:00:00Z");

    @TestConfiguration
    static class FakeSenderConfig {
        @Primary
        @Bean
        FakeOutboxMessageSender fakeOutboxMessageSender() {
            return new FakeOutboxMessageSender();
        }
    }

    /** 결과를 주입하는 가짜 전송기. 보낸 (key, payload) 를 기록하고, 필요하면 send 안에서 멈춰 겹침 장면을 만든다. */
    static class FakeOutboxMessageSender implements OutboxMessageSender {
        record Sent(String key, String payload) { }

        private final List<Sent> sent = new CopyOnWriteArrayList<>();
        private volatile Function<String, OutboxSendResult> resultByKey;
        private volatile CountDownLatch sendStarted;
        private volatile CountDownLatch releaseSend;

        FakeOutboxMessageSender() {
            reset();
        }

        void answer(Function<String, OutboxSendResult> resultByKey) {
            this.resultByKey = resultByKey;
        }

        /** 다음 send 가 들어오면 sendStarted 를 내리고 releaseSend 가 열릴 때까지 기다린다. */
        void holdSends(CountDownLatch sendStarted, CountDownLatch releaseSend) {
            this.sendStarted = sendStarted;
            this.releaseSend = releaseSend;
        }

        List<Sent> sent() {
            return List.copyOf(sent);
        }

        void reset() {
            sent.clear();
            resultByKey = key -> OutboxSendResult.published("order-events", 0, sent.size());
            sendStarted = null;
            releaseSend = null;
        }

        @Override
        public OutboxSendResult send(String messageKey, String payload) {
            CountDownLatch started = sendStarted;
            CountDownLatch release = releaseSend;
            if (started != null && release != null) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sent.add(new Sent(messageKey, payload));
            return resultByKey.apply(messageKey);
        }
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private OrderConfirmedOutboxRelay relay;
    @Autowired
    private OrderConfirmedOutboxRepository repository;
    @Autowired
    private FakeOutboxMessageSender fakeSender;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        fakeSender.reset();
        fixClock(NOW);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재시도 시간이 된 PENDING 을 집어, ")
    @Nested
    class HappyPath {
        @DisplayName("저장된 key·payload 그대로 보내고 broker 확인이 오면 PUBLISHED·publishedAt 으로 바꾼다. 회차 결과는 집은 수·발행 수를 돌려준다.")
        @Test
        void publishesDuePendingRows() {
            seedPending(1L, now());
            seedPending(2L, now());

            OutboxRelayResult result = relay.runOnce();

            assertThat(result.reason()).isEqualTo(OutboxRelayResult.Reason.COMPLETED);
            assertThat(result.picked()).isEqualTo(2);
            assertThat(result.published()).isEqualTo(2);
            assertThat(result.retried()).isZero();
            assertThat(result.blocked()).isZero();
            assertThat(result.skippedStale()).isZero();
            assertThat(result.deferred()).isZero();
            assertThat(result.remainingPending()).isZero();

            assertThat(fakeSender.sent()).containsExactly(
                new FakeOutboxMessageSender.Sent("1", outboxOf(1L).getPayload()),
                new FakeOutboxMessageSender.Sent("2", outboxOf(2L).getPayload()));
            for (long orderId : List.of(1L, 2L)) {
                OrderConfirmedOutbox row = outboxOf(orderId);
                assertThat(row.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
                assertThat(row.getPublishedAt().toInstant()).isEqualTo(NOW);
                assertThat(row.getAttemptCount()).isEqualTo(1);
                assertThat(row.getLastError()).isNull();
            }
        }

        @DisplayName("nextAttemptAt 이 아직 오지 않은 행과 이미 PUBLISHED 인 행은 집지 않는다. 끝난 행에 대한 상태 UPDATE 는 0행이다.")
        @Test
        void skipsNotDueAndFinishedRows() {
            seedPending(1L, now().plusSeconds(60));
            seedPending(2L, now());
            relay.runOnce();
            assertThat(outboxOf(2L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
            fakeSender.reset();

            OutboxRelayResult result = relay.runOnce();

            assertThat(result.picked()).isZero();
            assertThat(result.remainingPending()).isEqualTo(1);
            assertThat(fakeSender.sent()).isEmpty();
            assertThat(outboxOf(1L).getAttemptCount()).isZero();
            // 늦은 결과가 끝난 행을 덮어쓰지 못한다 (D25): PUBLISHED 행에 대한 상태 UPDATE 는 모두 0행
            Long doneId = outboxOf(2L).getId();
            assertThat(inTx(() -> repository.scheduleRetry(doneId, now(), "늦은 결과"))).isZero();
            assertThat(inTx(() -> repository.markBlocked(doneId, "늦은 결과"))).isZero();
            assertThat(inTx(() -> repository.startAttempt(doneId))).isZero();
            assertThat(outboxOf(2L).getLastError()).isNull();
            assertThat(outboxOf(2L).getAttemptCount()).isEqualTo(1);
        }
    }

    @DisplayName("broker 가 응답하지 않으면(전송 실패·미확인), ")
    @Nested
    class BrokerUnavailable {
        @DisplayName("그 행은 PENDING 유지·nextAttemptAt 재예약·lastError 기록 뒤 회차를 일찍 끝내고, 묶음의 나머지 행은 다음 회차로 미룬다(deferred).")
        @Test
        void schedulesRetry_andEndsRoundEarly() {
            seedPending(1L, now());
            seedPending(2L, now());
            fakeSender.answer(key -> OutboxSendResult.retry("결과 미확인: 1000ms 안에 broker 확인을 받지 못함"));

            OutboxRelayResult result = relay.runOnce();

            assertThat(result.reason()).isEqualTo(OutboxRelayResult.Reason.BROKER_UNAVAILABLE);
            assertThat(result.picked()).isEqualTo(2);
            assertThat(result.retried()).isEqualTo(1);
            assertThat(result.deferred()).isEqualTo(1);
            assertThat(result.skippedStale()).isZero();
            assertThat(result.published()).isZero();
            assertThat(result.remainingPending()).isEqualTo(2);
            assertThat(fakeSender.sent()).hasSize(1);

            OrderConfirmedOutbox rowA = outboxOf(1L);
            assertThat(rowA.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
            assertThat(rowA.getAttemptCount()).isEqualTo(1);
            assertThat(rowA.getLastError()).isEqualTo("결과 미확인: 1000ms 안에 broker 확인을 받지 못함");
            assertThat(rowA.getNextAttemptAt().toInstant()).isEqualTo(NOW); // test 프로파일 backoff 0초
            OrderConfirmedOutbox rowB = outboxOf(2L);
            assertThat(rowB.getAttemptCount()).isZero();
            assertThat(rowB.getLastError()).isNull();
        }

        @DisplayName("[운영 시작값·고정 Clock] 같은 행이 계속 실패하면 nextAttemptAt 이 10초 → 20초 → 40초 → … → 5분 상한으로 늘어난다.")
        @Test
        void backsOffWithProductionValues_underControlledClock() {
            OrderConfirmedOutboxRelay productionRelay = new OrderConfirmedOutboxRelay(
                repository, fakeSender,
                new OutboxRelayProperties(false, Duration.ofSeconds(10), 20, Duration.ofSeconds(3), Duration.ofSeconds(10), 2, Duration.ofMinutes(5)),
                clock, transactionManager);
            seedPending(1L, now());
            fakeSender.answer(key -> OutboxSendResult.retry("실패 확정: TimeoutException - Expiring 1 record(s)"));

            long[] expectedDelaysSeconds = {10, 20, 40, 80, 160, 300, 300};
            Instant current = NOW;
            for (int i = 0; i < expectedDelaysSeconds.length; i++) {
                fixClock(current);
                OutboxRelayResult result = productionRelay.runOnce();

                assertThat(result.picked()).as("회차 %d 집은 수", i + 1).isEqualTo(1);
                assertThat(result.retried()).as("회차 %d 재시도 예약", i + 1).isEqualTo(1);
                OrderConfirmedOutbox row = outboxOf(1L);
                assertThat(row.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
                assertThat(row.getAttemptCount()).isEqualTo(i + 1);
                assertThat(Duration.between(current, row.getNextAttemptAt().toInstant()).toSeconds())
                    .as("회차 %d 뒤 다음 시도까지 간격", i + 1).isEqualTo(expectedDelaysSeconds[i]);
                current = row.getNextAttemptAt().toInstant();
            }
            assertThat(fakeSender.sent()).hasSize(expectedDelaysSeconds.length);
        }
    }

    @DisplayName("영구 오류(다시 보내도 같은 결과)는, ")
    @Nested
    class PermanentError {
        @DisplayName("그 행만 BLOCKED·lastError 로 바꾸고 다음 행은 계속 처리한다.")
        @Test
        void blocksOnlyThatRow_andContinues() {
            seedPending(1L, now());
            seedPending(2L, now());
            fakeSender.answer(key -> "1".equals(key)
                ? OutboxSendResult.blocked("실패 확정: RecordTooLargeException - The message is 1100000 bytes")
                : OutboxSendResult.published("order-events", 0, 7));

            OutboxRelayResult result = relay.runOnce();

            assertThat(result.reason()).isEqualTo(OutboxRelayResult.Reason.COMPLETED);
            assertThat(result.blocked()).isEqualTo(1);
            assertThat(result.published()).isEqualTo(1);
            assertThat(result.remainingPending()).isZero();
            assertThat(outboxOf(1L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.BLOCKED);
            assertThat(outboxOf(1L).getLastError()).contains("RecordTooLargeException");
            assertThat(outboxOf(2L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
        }

        @DisplayName("BLOCKED 행은 relay 가 집지 않으며, resume(eventId) 가 PENDING 으로 되돌려 한 회차를 돌리면 발행된다.")
        @Test
        void resumeReturnsBlockedRowToPending_andRuns() {
            seedPending(1L, now());
            fakeSender.answer(key -> OutboxSendResult.blocked("실패 확정: InvalidTopicException"));
            relay.runOnce();
            assertThat(outboxOf(1L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.BLOCKED);
            assertThat(relay.runOnce().picked()).isZero();
            fakeSender.reset();

            OutboxRelayResult result = relay.resume(outboxOf(1L).getEventId());

            assertThat(result.published()).isEqualTo(1);
            OrderConfirmedOutbox row = outboxOf(1L);
            assertThat(row.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
            assertThat(row.getAttemptCount()).isEqualTo(2);
            assertThat(fakeSender.sent()).hasSize(1);
        }

        @DisplayName("없는 eventId 의 resume 은 NOT_FOUND 로 드러낸다.")
        @Test
        void resumeRejectsUnknownEventId() {
            assertThatThrownBy(() -> relay.resume("order-confirmed:v1:999"))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.NOT_FOUND);
            assertThat(fakeSender.sent()).isEmpty();
        }

        @DisplayName("BLOCKED 가 아닌 행(PENDING·PUBLISHED)의 resume 은 CONFLICT 로 드러내고 상태를 바꾸지 않는다.")
        @Test
        void resumeRejectsNonBlockedRow() {
            seedPending(1L, now());                    // 이번 회차에 발행돼 PUBLISHED 가 될 행
            seedPending(2L, now().plusSeconds(60));    // 기한이 안 돼 PENDING 으로 남는 행
            relay.runOnce();
            assertThat(outboxOf(1L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
            assertThat(outboxOf(2L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
            fakeSender.reset();

            assertThatThrownBy(() -> relay.resume(outboxOf(1L).getEventId()))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThatThrownBy(() -> relay.resume(outboxOf(2L).getEventId()))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(fakeSender.sent()).isEmpty();
            assertThat(outboxOf(1L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
            assertThat(outboxOf(2L).getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
        }
    }

    @DisplayName("같은 프로세스에서 회차가 겹치면 두 번째는 집지 않고 ALREADY_RUNNING 으로 돌아간다 (프로세스 안 보호일 뿐 분산 잠금은 아니다).")
    @Test
    void secondRunReturnsAlreadyRunning_whileFirstIsSending() throws Exception {
        seedPending(1L, now());
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        fakeSender.holdSends(sendStarted, releaseSend);
        CompletableFuture<OutboxRelayResult> first = CompletableFuture.supplyAsync(relay::runOnce);
        assertThat(sendStarted.await(5, TimeUnit.SECONDS)).as("첫 회차가 send 에 들어왔다").isTrue();

        OutboxRelayResult second = relay.runOnce();
        releaseSend.countDown();
        OutboxRelayResult firstResult = first.get(10, TimeUnit.SECONDS);

        assertThat(second.reason()).isEqualTo(OutboxRelayResult.Reason.ALREADY_RUNNING);
        assertThat(second.picked()).isZero();
        assertThat(firstResult.published()).isEqualTo(1);
        assertThat(fakeSender.sent()).hasSize(1);
    }

    // ---- helpers ----

    /** Outbox 행을 직접 심는다 (relay 규칙 검증용. 재현·복구 테스트는 확정 경로를 실제로 탄다). */
    private void seedPending(long orderId, ZonedDateTime nextAttemptAt) {
        OrderConfirmedEvent event = OrderConfirmedEvent.of(orderId, nextAttemptAt, List.of(new OrderItem(7L, 2, 1000L)));
        String payload = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"" + event.eventId() + "\",\"orderId\":" + orderId
            + ",\"confirmedAt\":\"" + event.confirmedAt() + "\",\"items\":[{\"productId\":7,\"quantity\":2}]}";
        inTx(() -> repository.save(OrderConfirmedOutbox.pending(event, payload, nextAttemptAt)));
    }

    private OrderConfirmedOutbox outboxOf(long orderId) {
        return repository.findByOrderId(orderId).orElseThrow();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    private void fixClock(Instant instant) {
        given(clock.instant()).willReturn(instant);
        given(clock.getZone()).willReturn(ZONE);
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
