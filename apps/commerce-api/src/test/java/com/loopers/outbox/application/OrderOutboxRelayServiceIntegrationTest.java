package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.application.port.out.MessageSendPort.SentPosition;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxBackoff;
import com.loopers.outbox.domain.OrderOutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * relay 한 회차 (docs/week7/plan.md D-1, 핵심 2). DB는 진짜(MySQL), broker는 결과를 마음대로 정하는 가짜 send port다.
 * <p>
 * 가장 중요한 성질: <b>broker 응답(ack)을 받은 뒤에만 PUBLISHED</b>. 실패·timeout·ack 뒤 저장 실패에서 행은 PUBLISHED가 아니고,
 * 다시 골라져 같은 eventId·payload로 나간다 (누락 대신 중복 → consumer 멱등성이 거른다).
 * <p>
 * 시각은 고정한 Clock, backoff 대기는 고정 10초. send-wait는 timeout 사례를 빨리 보려고 300ms로 줄인다.
 */
@SpringBootTest(properties = {"outbox.relay.batch-size=2", "outbox.relay.send-wait=300ms"})
class OrderOutboxRelayServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 11, 6, 10, 0, 0, 0, KST);
    private static final Duration DELAY = Duration.ofSeconds(10);
    private static final String TOPIC = "order-events";

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @MockitoSpyBean
    private OrderOutboxPort orderOutboxPort;

    @MockitoBean
    private MessageSendPort messageSendPort;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private OrderOutboxBackoff backoff;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        at(T0);
        when(clock.getZone()).thenReturn(KST);
        when(backoff.delayAfter(anyInt())).thenReturn(DELAY);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void at(ZonedDateTime now) {
        when(clock.instant()).thenReturn(now.toInstant());
    }

    /** orderId n → eventId "e-n", key "n", payload {"orderId":n} */
    private void savePending(long orderId) {
        orderOutboxPort.save(OrderOutbox.pending("e-" + orderId, orderId, "OrderConfirmed", 1, payload(orderId), T0));
    }

    private static String payload(long orderId) {
        return "{\"orderId\":" + orderId + "}";
    }

    private OrderOutbox row(long orderId) {
        return orderOutboxPort.findByEventId("e-" + orderId).orElseThrow();
    }

    private void ackFor(long orderId, int partition, long offset) {
        when(messageSendPort.send(TOPIC, String.valueOf(orderId), payload(orderId)))
            .thenReturn(CompletableFuture.completedFuture(new SentPosition(partition, offset)));
    }

    private void failFor(long orderId, RuntimeException cause) {
        when(messageSendPort.send(TOPIC, String.valueOf(orderId), payload(orderId)))
            .thenReturn(CompletableFuture.failedFuture(cause));
    }

    private void verifyNotSent(long orderId) {
        verify(messageSendPort, never()).send(anyString(), eq(String.valueOf(orderId)), anyString());
    }

    @DisplayName("broker가 확인하면(ack), ")
    @Nested
    class Ack {

        @DisplayName("C-4 차례가 된 행을 id 순서로 저장된 key·payload 그대로 보내고, ack의 partition·offset으로 PUBLISHED를 기록한다.")
        @Test
        void sendsStoredIdentityInOrderAndRecordsAck() {
            // arrange
            savePending(1L);
            savePending(2L);
            ackFor(1L, 0, 10L);
            ackFor(2L, 2, 20L);

            // act
            RelayResult result = relay.relayOnce();

            // assert
            assertThat(result).isEqualTo(new RelayResult(2, 2, null));
            InOrder order = inOrder(messageSendPort);
            order.verify(messageSendPort).send(TOPIC, "1", payload(1L));
            order.verify(messageSendPort).send(TOPIC, "2", payload(2L));
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(row(1L).getPublishedPartition()).isEqualTo(0);
            assertThat(row(1L).getPublishedOffset()).isEqualTo(10L);
            assertThat(row(2L).getPublishedPartition()).isEqualTo(2);
            assertThat(row(2L).getPublishedOffset()).isEqualTo(20L);
            assertThat(row(1L).getPublishedAt().toInstant()).isEqualTo(T0.toInstant());
        }

        @DisplayName("D-1 한 회차는 최대 batch-size(테스트 2)개만 고른다. 나머지는 다음 회차가 보낸다.")
        @Test
        void sendsAtMostBatchSize() {
            // arrange
            savePending(1L);
            savePending(2L);
            savePending(3L);
            ackFor(1L, 0, 1L);
            ackFor(2L, 0, 2L);
            ackFor(3L, 0, 3L);

            // act
            RelayResult first = relay.relayOnce();
            RelayResult second = relay.relayOnce();

            // assert
            assertThat(first).isEqualTo(new RelayResult(2, 2, null));
            assertThat(second).isEqualTo(new RelayResult(1, 1, null));
            assertThat(row(3L).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        }

        @DisplayName("PUBLISHED는 다시 보내지 않는다 — 다음 회차는 아무것도 고르지 않는다.")
        @Test
        void doesNotResendPublished() {
            // arrange
            savePending(1L);
            ackFor(1L, 0, 1L);
            relay.relayOnce();

            // act
            RelayResult again = relay.relayOnce();

            // assert
            assertThat(again).isEqualTo(new RelayResult(0, 0, null));
            verify(messageSendPort, times(1)).send(TOPIC, "1", payload(1L));
        }
    }

    @DisplayName("send가 실패하면, ")
    @Nested
    class Failure {

        @DisplayName("D-1·D-3 broker가 실패를 알리면 PUBLISHED가 아니다 — PENDING, 시도 1, 10초 뒤 차례, 원인이 남고, 회차를 멈춰 뒤 행을 보내지 않는다.")
        @Test
        void failedFutureLeavesPendingAndStopsRound() {
            // arrange
            savePending(1L);
            savePending(2L);
            failFor(1L, new TimeoutException("Expiring 1 record(s) after 30000 ms"));

            // act
            RelayResult result = relay.relayOnce();

            // assert
            assertThat(result).isEqualTo(new RelayResult(2, 0, "e-1"));
            OrderOutbox failed = row(1L);
            assertThat(failed.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(failed.getAttemptCount()).isEqualTo(1);
            assertThat(failed.getNextAttemptAt().toInstant()).isEqualTo(T0.plus(DELAY).toInstant());
            assertThat(failed.getLastError()).contains("Expiring 1 record(s) after 30000 ms");
            assertThat(failed.getPublishedAt()).isNull();
            verifyNotSent(2L);
            assertThat(row(2L).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        }

        @DisplayName("D-1 send() 호출 자체가 예외를 던져도(metadata 없음, max.block.ms) 같은 실패로 기록한다.")
        @Test
        void synchronousSendExceptionIsFailure() {
            // arrange
            savePending(1L);
            when(messageSendPort.send(TOPIC, "1", payload(1L)))
                .thenThrow(new TimeoutException("Topic order-events not present in metadata after 1000 ms."));

            // act
            RelayResult result = relay.relayOnce();

            // assert
            assertThat(result.stoppedAt()).isEqualTo("e-1");
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(row(1L).getAttemptCount()).isEqualTo(1);
            assertThat(row(1L).getLastError()).contains("not present in metadata");
        }

        @DisplayName("D-1 send-wait 안에 응답이 없으면(future가 끝나지 않음) 실패로 기록한다 — PUBLISHED가 아니다.")
        @Test
        void noResponseWithinSendWaitIsFailure() {
            // arrange
            savePending(1L);
            when(messageSendPort.send(TOPIC, "1", payload(1L))).thenReturn(new CompletableFuture<>());

            // act
            RelayResult result = relay.relayOnce();

            // assert
            assertThat(result.stoppedAt()).isEqualTo("e-1");
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(row(1L).getAttemptCount()).isEqualTo(1);
            assertThat(row(1L).getPublishedAt()).isNull();
        }

        @DisplayName("D-1 다음 회차는 재시도 대기 중인 행을 건너뛰고 뒤 행을 보낸다 — 늦어지는 것은 실패한 한 건뿐이다.")
        @Test
        void nextRoundSkipsWaitingRowAndSendsTheRest() {
            // arrange
            savePending(1L);
            savePending(2L);
            failFor(1L, new TimeoutException("broker down"));
            ackFor(2L, 0, 2L);
            relay.relayOnce();

            // act — 1초 뒤 (1번은 T0+10s까지 대기)
            at(T0.plusSeconds(1));
            RelayResult second = relay.relayOnce();

            // assert
            assertThat(second).isEqualTo(new RelayResult(1, 1, null));
            assertThat(row(2L).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            verify(messageSendPort, times(1)).send(TOPIC, "1", payload(1L));
        }

        @DisplayName("D-3 대기가 끝나면 같은 eventId의 같은 payload를 다시 보내고, 이번에 ack를 받으면 PUBLISHED다.")
        @Test
        void retriesSamePayloadAfterDelay() {
            // arrange
            savePending(1L);
            failFor(1L, new TimeoutException("broker down"));
            relay.relayOnce();
            ackFor(1L, 1, 5L);

            // act
            at(T0.plus(DELAY));
            RelayResult retry = relay.relayOnce();

            // assert
            assertThat(retry).isEqualTo(new RelayResult(1, 1, null));
            verify(messageSendPort, times(2)).send(TOPIC, "1", payload(1L));
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(row(1L).getAttemptCount()).isEqualTo(1);
        }

        @DisplayName("D-3 세 번째 실패면 BLOCKED가 되고, 그 뒤 회차는 그 행을 보내지 않는다.")
        @Test
        void thirdFailureBlocksAndIsNotSentAgain() {
            // arrange
            savePending(1L);
            failFor(1L, new TimeoutException("broker down"));
            relay.relayOnce();
            at(T0.plus(DELAY));
            relay.relayOnce();
            at(T0.plus(DELAY).plus(DELAY));

            // act
            relay.relayOnce();
            at(T0.plusHours(1));
            RelayResult later = relay.relayOnce();

            // assert
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED);
            assertThat(row(1L).getAttemptCount()).isEqualTo(3);
            assertThat(later).isEqualTo(new RelayResult(0, 0, null));
            verify(messageSendPort, times(3)).send(TOPIC, "1", payload(1L));
        }
    }

    @DisplayName("broker는 받았는데 PUBLISHED 저장이 실패하면(C-5 두 번째 중단 지점), ")
    @Nested
    class AckButSaveFails {

        @DisplayName("행은 SENDING으로 남고(lease 기한 T0+1분), 회차를 멈춘다 — PUBLISHED로 거짓 기록하지 않고, 실패 횟수도 늘지 않는다.")
        @Test
        void staysSendingWithLease() {
            // arrange
            savePending(1L);
            savePending(2L);
            ackFor(1L, 0, 10L);
            failNextPublishedSave();

            // act
            RelayResult result = relay.relayOnce();

            // assert
            assertThat(result).isEqualTo(new RelayResult(2, 0, "e-1"));
            OrderOutbox stuck = row(1L);
            assertThat(stuck.getStatus()).isEqualTo(OrderOutboxStatus.SENDING);
            assertThat(stuck.getNextAttemptAt().toInstant()).isEqualTo(T0.plus(OrderOutbox.LEASE).toInstant());
            assertThat(stuck.getAttemptCount()).isZero();
            assertThat(stuck.getPublishedAt()).isNull();
            verifyNotSent(2L);
        }

        @DisplayName("lease가 남은 동안은 다시 보내지 않고, 기한이 지나면 같은 eventId·payload로 다시 보낸다(중복 → consumer가 거른다).")
        @Test
        void resendsSamePayloadAfterLease() {
            // arrange
            savePending(1L);
            ackFor(1L, 0, 10L);
            failNextPublishedSave();
            relay.relayOnce();

            // act
            at(T0.plusSeconds(59));
            RelayResult withinLease = relay.relayOnce();
            at(T0.plus(OrderOutbox.LEASE));
            RelayResult afterLease = relay.relayOnce();

            // assert
            assertThat(withinLease).isEqualTo(new RelayResult(0, 0, null));
            assertThat(afterLease).isEqualTo(new RelayResult(1, 1, null));
            verify(messageSendPort, times(2)).send(TOPIC, "1", payload(1L));
            assertThat(row(1L).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        }

        /** 다음 한 번의 "PUBLISHED 행 저장"만 DB 장애처럼 실패시킨다. 그 TX는 rollback된다 */
        private void failNextPublishedSave() {
            doThrow(new IllegalStateException("DB down"))
                .doCallRealMethod()
                .when(orderOutboxPort).save(argThat(o -> o != null && o.getStatus() == OrderOutboxStatus.PUBLISHED));
        }
    }
}
