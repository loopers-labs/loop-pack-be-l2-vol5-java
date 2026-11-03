package com.loopers.order.infrastructure.outbox;

import com.loopers.order.application.outbox.PublishResult;
import com.loopers.order.application.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OrderConfirmed Outbox relay 상태 전이")
class OrderConfirmedOutboxRelayTest {

    private static final String MESSAGE_KEY = "42";
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:42\"}";
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 3;

    private final OrderConfirmedOutboxRepository repository = mock(OrderConfirmedOutboxRepository.class);
    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));

    private OrderConfirmedOutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            clock,
            RETRY_INTERVAL,
            BATCH_SIZE,
            MAX_ATTEMPTS
        );
    }

    @Test
    @DisplayName("발행 future가 완료되기 전에는 상태를 바꾸지 않고 완료 후 결과에 따라 전이한다")
    void waitsForPublisherFutureBeforeTransitioningOutbox() throws Exception {
        OrderConfirmedOutbox outbox = pendingOutbox();
        when(repository.findReadyForRelay(NOW, BATCH_SIZE)).thenReturn(List.of(outbox));
        CompletableFuture<PublishResult> publishFuture = new CompletableFuture<>();
        CountDownLatch publishStarted = new CountDownLatch(1);
        when(publisher.publish(MESSAGE_KEY, PAYLOAD)).thenAnswer(invocation -> {
            publishStarted.countDown();
            return publishFuture;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var relayExecution = executor.submit(relay::pollOnce);
            assertThat(publishStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
            assertThat(outbox.getPublishedAt()).isNull();

            publishFuture.complete(PublishResult.SUCCESS);
            relayExecution.get(2, TimeUnit.SECONDS);

            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
            verify(repository).saveAndFlush(outbox);
        } finally {
            if (!publishFuture.isDone()) {
                publishFuture.completeExceptionally(new TimeoutException("test cleanup"));
            }
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "발행 결과 {0}, 기존 시도 {1}회이면 Outbox를 {2}로 전이한다")
    @MethodSource("publishResultTransitions")
    void transitionsOutboxAccordingToPublisherResult(
        PublishResult publishResult,
        int initialAttemptCount,
        OrderConfirmedOutboxStatus expectedStatus
    ) {
        OrderConfirmedOutbox outbox = pendingOutbox();
        outbox.setAttemptCount(initialAttemptCount);
        when(repository.findReadyForRelay(NOW, BATCH_SIZE)).thenReturn(List.of(outbox));
        when(publisher.publish(MESSAGE_KEY, PAYLOAD))
            .thenReturn(CompletableFuture.completedFuture(publishResult));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(expectedStatus);
        assertThat(outbox.getAttemptCount()).isEqualTo(initialAttemptCount + 1);
        if (expectedStatus == OrderConfirmedOutboxStatus.PUBLISHED) {
            assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
        } else {
            assertThat(outbox.getPublishedAt()).isNull();
        }
        verify(publisher).publish(MESSAGE_KEY, PAYLOAD);
        verify(repository).saveAndFlush(outbox);
    }

    private Stream<org.junit.jupiter.params.provider.Arguments> publishResultTransitions() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                PublishResult.SUCCESS,
                0,
                OrderConfirmedOutboxStatus.PUBLISHED
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                PublishResult.FAILED,
                0,
                OrderConfirmedOutboxStatus.FAILED
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                PublishResult.PENDING,
                0,
                OrderConfirmedOutboxStatus.PENDING
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                PublishResult.FAILED,
                MAX_ATTEMPTS - 1,
                OrderConfirmedOutboxStatus.BLOCKED
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                PublishResult.PENDING,
                MAX_ATTEMPTS - 1,
                OrderConfirmedOutboxStatus.BLOCKED
            )
        );
    }

    private OrderConfirmedOutbox pendingOutbox() {
        return OrderConfirmedOutbox.pending(
            "order-confirmed:42",
            42L,
            "OrderConfirmed",
            1,
            MESSAGE_KEY,
            PAYLOAD,
            NOW
        );
    }
}
