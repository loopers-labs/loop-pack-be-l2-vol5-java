package com.loopers.order.infrastructure.outbox;

import com.loopers.order.application.outbox.OutboxPublisher;
import com.loopers.order.application.outbox.PublishResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = OrderConfirmedOutboxRelayDocumentContractTest.TestConfiguration.class)
@ActiveProfiles("test")
class OrderConfirmedOutboxRelayDocumentContractTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Autowired
    private Environment environment;
    private OrderConfirmedOutboxRepository repository;
    private OutboxPublisher publisher;
    private OrderConfirmedOutboxRelay relay;
    private RelaySettings settings;

    @BeforeEach
    void setUp() {
        repository = mock(OrderConfirmedOutboxRepository.class);
        publisher = mock(OutboxPublisher.class);
        settings = new RelaySettings(
            DurationStyle.detectAndParse(environment.getRequiredProperty("outbox.relay.poll-interval")),
            environment.getRequiredProperty("outbox.relay.batch-size", Integer.class),
            environment.getRequiredProperty("outbox.relay.max-attempts", Integer.class)
        );
        configureRelay(settings);
    }

    @Test
    @DisplayName("재시도 가능 시각이 지난 이벤트를 설정된 최대 건수만큼 조회해 순차 처리한다")
    void findsAtMostConfiguredBatchAtCurrentTimeAndProcessesRowsSequentially() {
        OrderConfirmedOutbox first = outbox("event-1", NOW);
        OrderConfirmedOutbox second = outbox("event-2", NOW);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(first, second));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.SUCCESS));
        when(publisher.publish("order-2", "payload-2"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.SUCCESS));

        relay.pollOnce();

        InOrder order = inOrder(repository, publisher);
        order.verify(repository).findReadyForRelay(NOW, settings.batchSize());
        order.verify(repository).save(first);
        order.verify(publisher).publish("order-1", "payload-1");
        order.verify(repository).saveAndFlush(first);
        order.verify(repository).save(second);
        order.verify(publisher).publish("order-2", "payload-2");
        order.verify(repository).saveAndFlush(second);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(second.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kafka 성공 응답을 받으면 시도 횟수를 올리고 PUBLISHED와 발행 완료 시각을 기록한다")
    void recordsPublishedStateAndCompletionTimeAfterSuccessResponse() {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.SUCCESS));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        verify(repository).saveAndFlush(outbox);
    }

    @Test
    @DisplayName("Kafka 실패 응답을 받으면 최대 시도 전까지 FAILED와 다음 시도 시각을 기록한다")
    void recordsFailedResponseAndSchedulesRetryBeforeMaximumAttempts() {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.failed("BrokerException")));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.FAILED);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plus(settings.pollInterval()));
        assertThat(outbox.getLastError()).isEqualTo("BrokerException");
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("발행 성공 여부를 확정할 응답을 받지 못하면 최대 시도 전까지 PENDING으로 재시도한다")
    void keepsUncertainResultPendingAndSchedulesRetryBeforeMaximumAttempts() {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.pending("TimeoutException")));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plus(settings.pollInterval()));
        assertThat(outbox.getLastError()).isEqualTo("TimeoutException");
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("성공으로 확정되지 않은 발행 시도가 최대 횟수에 도달하면 BLOCKED로 전환한다")
    void blocksUnsuccessfulAttemptWhenItReachesMaximumAttempts() {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        outbox.setAttemptCount(settings.maxAttempts() - 1);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.pending("TimeoutException")));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.BLOCKED);
        assertThat(outbox.getAttemptCount()).isEqualTo(settings.maxAttempts());
        assertThat(outbox.getLastError()).isEqualTo("TimeoutException");
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("최대 시도 횟수에 도달했더라도 Kafka 성공 응답이면 PUBLISHED로 기록한다")
    void successAtMaximumAttemptIsStillPublished() {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        outbox.setAttemptCount(settings.maxAttempts() - 1);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.SUCCESS));

        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
        assertThat(outbox.getAttemptCount()).isEqualTo(settings.maxAttempts());
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("기본 설정은 폴링 간격 1초, 조회 묶음 크기 10건, 최대 시도 횟수 3회다")
    void readsDocumentedDefaultRelaySettingsFromTestProfile() {
        assertThat(settings.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.batchSize()).isEqualTo(10);
        assertThat(settings.maxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("테스트별로 폴링 간격, 조회 묶음 크기, 최대 시도 횟수를 지정할 수 있다")
    void allowsRelaySettingsToBeChangedPerTest() {
        configureRelay(settings
            .withPollInterval(Duration.ofSeconds(7))
            .withBatchSize(4)
            .withMaxAttempts(2));
        OrderConfirmedOutbox retryable = outbox("event-1", NOW);
        OrderConfirmedOutbox lastAttempt = outbox("event-2", NOW);
        lastAttempt.setAttemptCount(1);
        when(repository.findReadyForRelay(NOW, 4)).thenReturn(List.of(retryable, lastAttempt));
        when(publisher.publish("order-1", "payload-1"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.failed("BrokerException")));
        when(publisher.publish("order-2", "payload-2"))
            .thenReturn(CompletableFuture.completedFuture(PublishResult.pending("TimeoutException")));

        relay.pollOnce();

        assertThat(retryable.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.FAILED);
        assertThat(retryable.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(7));
        assertThat(lastAttempt.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.BLOCKED);
        assertThat(lastAttempt.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Kafka 응답이 도착하기 전에는 발행 시도를 완료 처리하지 않는다")
    void waitsForPublisherResponseBeforeRecordingResult() throws InterruptedException {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        CompletableFuture<PublishResult> response = new CompletableFuture<>();
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1")).thenReturn(response);

        Thread relayThread = new Thread(relay::pollOnce);
        relayThread.start();
        try {
            for (int attempt = 0; attempt < 100 && !publisherWasCalled(outbox); attempt++) {
                TimeUnit.MILLISECONDS.sleep(10);
            }

            assertThat(relayThread.isAlive()).isTrue();
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
            verify(repository).save(outbox);
            verify(repository, org.mockito.Mockito.never()).saveAndFlush(outbox);

            response.complete(PublishResult.SUCCESS);
            relayThread.join(1_000);

            assertThat(relayThread.isAlive()).isFalse();
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
            verify(repository).saveAndFlush(outbox);
        } finally {
            response.complete(PublishResult.SUCCESS);
            relayThread.join(1_000);
        }
    }

    private boolean publisherWasCalled(OrderConfirmedOutbox outbox) {
        try {
            verify(publisher).publish(outbox.getMessageKey(), outbox.getPayload());
            return true;
        } catch (org.mockito.exceptions.verification.WantedButNotInvoked ignored) {
            return false;
        }
    }

    private OrderConfirmedOutbox outbox(String eventId, Instant createdAt) {
        return OrderConfirmedOutbox.pending(
            eventId,
            1L,
            "OrderConfirmed",
            1,
            eventId.replace("event-", "order-"),
            "payload-" + eventId.replace("event-", ""),
            createdAt
        );
    }

    private void configureRelay(RelaySettings relaySettings) {
        settings = relaySettings;
        relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            settings.pollInterval(),
            settings.batchSize(),
            settings.maxAttempts()
        );
    }

    private record RelaySettings(Duration pollInterval, int batchSize, int maxAttempts) {

        private RelaySettings withPollInterval(Duration value) {
            return new RelaySettings(value, batchSize, maxAttempts);
        }

        private RelaySettings withBatchSize(int value) {
            return new RelaySettings(pollInterval, value, maxAttempts);
        }

        private RelaySettings withMaxAttempts(int value) {
            return new RelaySettings(pollInterval, batchSize, value);
        }
    }

    @SpringBootConfiguration
    static class TestConfiguration {
    }
}
