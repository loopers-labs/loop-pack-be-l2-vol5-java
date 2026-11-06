package com.loopers.application.outbox;

import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.SentRecord;
import com.loopers.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * relay 회차의 순서 규칙만 본다(docs/week7/decisions.md D-2, D-4). DB·Kafka 없이 포트와 결과 기록 빈을 mock으로 둔다.
 * 실제 DB·broker를 지나는 장면은 {@link OutboxRelayTest}가 본다.
 */
class OutboxRelayUnitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxDeliveryLedger ledger = mock(OutboxDeliveryLedger.class);
    private final OrderConfirmedPublisher publisher = mock(OrderConfirmedPublisher.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final OutboxRelay relay = new OutboxRelay(repository, ledger, publisher, CLOCK, 10, Duration.ofMinutes(1));

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static ClaimedOutbox claimed(Long id) {
        return new ClaimedOutbox(id, "order-confirmed:v1:" + id, id, String.valueOf(id), "{}", 1,
            ZonedDateTime.now(CLOCK));
    }

    @DisplayName("앞 회차가 send를 기다리는 동안 들어온 회차(스케줄·수동 재개 모두)는 아무것도 하지 않고 건너뛴다. "
        + "앞 회차가 끝나면 다음 회차는 다시 돈다. (D-4)")
    @Test
    void secondRoundIsSkippedWhileFirstIsInProgress() throws Exception {
        // arrange
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(publisher.maxSendTime()).thenReturn(Duration.ofSeconds(125));
        when(repository.findDueIds(any(), anyInt())).thenReturn(List.of(1L));
        when(repository.findOldestCreatedAt(any())).thenReturn(Optional.empty());
        when(ledger.claim(eq(1L), any(), any())).thenReturn(Optional.of(claimed(1L)));
        when(publisher.send(anyString(), anyString())).thenAnswer(invocation -> {
            sending.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new SentRecord("t", 0, 0L);
        });

        // act
        Future<OutboxRelayRound> first = executor.submit(relay::runOnce);
        assertThat(sending.await(10, TimeUnit.SECONDS)).as("첫 회차가 send에 들어감").isTrue();
        OutboxRelayRound scheduled = relay.runOnce();
        OutboxRelayRound manual = relay.runOnceFor("order-confirmed:v1:1");
        release.countDown();
        OutboxRelayRound firstRound = first.get(10, TimeUnit.SECONDS);

        // assert
        assertThat(scheduled.skipped()).isTrue();
        assertThat(manual.skipped()).isTrue();
        assertThat(firstRound.skipped()).isFalse();
        assertThat(firstRound.sent()).isEqualTo(1);
        verify(repository, times(1)).findDueIds(any(), anyInt());
        verify(repository, times(0)).findByEventId(anyString());
        verify(publisher, times(1)).send(anyString(), anyString());

        // 앞 회차가 끝나면 장치가 풀린다
        when(repository.findDueIds(any(), anyInt())).thenReturn(List.of());
        assertThat(relay.runOnce().skipped()).isFalse();
    }

    @DisplayName("PUBLISHED 저장이 실패해도 회차는 멈추지 않고 다음 행을 보낸다. 실패한 행은 임대가 걸린 PENDING으로 남는다. (D-2)")
    @Test
    void continuesWhenMarkPublishedFails() {
        // arrange
        when(publisher.maxSendTime()).thenReturn(Duration.ofSeconds(125));
        when(repository.findDueIds(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(repository.findOldestCreatedAt(any())).thenReturn(Optional.empty());
        when(ledger.claim(eq(1L), any(), any())).thenReturn(Optional.of(claimed(1L)));
        when(ledger.claim(eq(2L), any(), any())).thenReturn(Optional.of(claimed(2L)));
        when(publisher.send(anyString(), anyString())).thenReturn(new SentRecord("t", 0, 0L));
        doThrow(new IllegalStateException("주입된 DB 실패")).when(ledger).markPublished(eq(1L), any(), any());

        // act
        OutboxRelayRound round = relay.runOnce();

        // assert
        assertThat(round.sent()).isEqualTo(2);
        verify(ledger).markPublished(eq(2L), any(), any());
        verify(ledger, times(0)).recordFailure(any(), anyString(), any());
    }

    @DisplayName("집기는 지금 + 임대(producer 최대 전송 시간 + 여유)로 한다. 백오프(1초)가 아니다. (D-2 바로잡은 것)")
    @Test
    void claimsWithLeaseNotBackoff() {
        // arrange
        when(publisher.maxSendTime()).thenReturn(Duration.ofSeconds(125));
        when(repository.findDueIds(any(), anyInt())).thenReturn(List.of(1L));
        when(repository.findOldestCreatedAt(any())).thenReturn(Optional.empty());
        when(ledger.claim(eq(1L), any(), any())).thenReturn(Optional.empty());

        // act
        OutboxRelayRound round = relay.runOnce();

        // assert
        ZonedDateTime now = ZonedDateTime.now(CLOCK);
        verify(ledger).claim(1L, now, now.plusSeconds(125).plusMinutes(1));
        assertThat(round.due()).isEqualTo(1);
        assertThat(round.picked()).isZero();
        verify(publisher, times(0)).send(anyString(), anyString());
    }
}
