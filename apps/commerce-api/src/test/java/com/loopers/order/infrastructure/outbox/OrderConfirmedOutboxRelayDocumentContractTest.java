package com.loopers.order.infrastructure.outbox;

import com.loopers.order.application.outbox.OutboxPublisher;
import com.loopers.order.application.outbox.PublishResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            settings.pollInterval(),
            settings.batchSize(),
            settings.maxAttempts()
        );
    }


    @Test
    @DisplayName("Kafka ACK를 받기 전에는 발행 결과를 완료 저장하지 않는다")
    void waitsForAckBeforeCompletingAttempt() throws Exception {
        OrderConfirmedOutbox outbox = outbox("event-1", NOW);
        CompletableFuture<PublishResult> publishResult = new CompletableFuture<>();
        CountDownLatch publishInvocationStarted = new CountDownLatch(1);
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish("order-1", "payload-1")).thenAnswer(invocation -> {
            publishInvocationStarted.countDown();
            return publishResult;
        });

        // When: Relay가 Kafka 발행을 시작한다.
        CompletableFuture<Void> relayExecution = CompletableFuture.runAsync(relay::pollOnce);
        try {
            assertThat(publishInvocationStarted.await(1, TimeUnit.SECONDS)).isTrue();

            // Then: ACK 이전에는 발행 완료로 기록하지 않는다.
            assertThatThrownBy(() -> relayExecution.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
            verify(repository, never()).saveAndFlush(outbox);

            // When: Kafka ACK가 도착한다.
            publishResult.complete(PublishResult.SUCCESS);
            relayExecution.get(1, TimeUnit.SECONDS);

            // Then: ACK 이후에만 발행 완료를 기록한다.
            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
            verify(repository).saveAndFlush(outbox);
        } finally {
            publishResult.complete(PublishResult.SUCCESS);
            relayExecution.handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Publisher Future가 미완료여도 Relay는 발행 제한 시간 안에 종료한다")
    void shouldNotWaitForever() throws Exception {
        OrderConfirmedOutbox outbox = outbox("event-no-response", NOW);
        CompletableFuture<PublishResult> neverCompletes = new CompletableFuture<>();
        when(repository.findReadyForRelay(NOW, settings.batchSize())).thenReturn(List.of(outbox));
        when(publisher.publish(anyString(), anyString())).thenReturn(neverCompletes);
        relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            settings.pollInterval(),
            Duration.ofMillis(100),
            settings.batchSize(),
            settings.maxAttempts()
        );

        CompletableFuture<Void> execution = CompletableFuture.runAsync(relay::pollOnce);
        try {
            execution.get(1, TimeUnit.SECONDS);

            assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
            assertThat(outbox.getLastError()).contains("Timeout");
        } finally {
            // 발행 제한 시간이 없는 구현으로 바뀌어도 테스트 스레드가 남지 않게 Future를 해제한다.
            neverCompletes.complete(PublishResult.pending("test-cleanup"));
            execution.handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("재시도해도 동일한 messageKey와 payload를 발행한다")
    void preservesMessageKeyAndPayloadAcrossRetries() {
        String eventId = "event-retry";
        String messageKey = "order-retry";
        String payload = "{\"eventId\":\"event-retry\",\"orderId\":42}";
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(
            eventId,
            42L,
            "OrderConfirmed",
            1,
            messageKey,
            payload,
            NOW
        );
        Instant retryAt = NOW.plus(settings.pollInterval());
        MutableClock clock = new MutableClock(NOW);
        when(repository.findReadyForRelay(org.mockito.ArgumentMatchers.any(Instant.class),
            org.mockito.ArgumentMatchers.eq(settings.batchSize())))
            .thenReturn(List.of(outbox), List.of(outbox));
        when(publisher.publish(anyString(), anyString()))
            .thenReturn(
                CompletableFuture.completedFuture(PublishResult.failed("BrokerException")),
                CompletableFuture.completedFuture(PublishResult.SUCCESS)
            );
        relay = new OrderConfirmedOutboxRelay(
            repository,
            publisher,
            clock,
            settings.pollInterval(),
            settings.batchSize(),
            settings.maxAttempts()
        );

        // 최초 발행 → 실패
        relay.pollOnce();
        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.FAILED);

        // 재시도 시각으로 이동
        clock.setInstant(retryAt);

        // 동일한 Relay로 재시도 → 성공
        relay.pollOnce();

        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PUBLISHED);
        verify(publisher, times(2)).publish(messageKey, payload);
    }

    @TestFactory
    @DisplayName("공통 발행 흐름 뒤에 응답 결과와 최대 시도 횟수에 따라 분기한다")
    Stream<DynamicTest> branchesByPublishResultAndAttemptLimit() {
        RelaySettings scenarioSettings = settings;
        return Stream.of(
                new PublishScenario("성공 응답은 최대 시도 전 PUBLISHED로 기록한다",
                    PublishResult.SUCCESS, 0, OrderConfirmedOutboxStatus.PUBLISHED, null),
                new PublishScenario("성공 응답은 최대 시도에 도달해도 PUBLISHED로 기록한다",
                    PublishResult.SUCCESS, scenarioSettings.maxAttempts() - 1,
                    OrderConfirmedOutboxStatus.PUBLISHED, null),
                new PublishScenario("실패 응답은 최대 시도 전 FAILED로 두고 재시도 시각을 기록한다",
                    PublishResult.failed("BrokerException"), 0,
                    OrderConfirmedOutboxStatus.FAILED, "BrokerException"),
                new PublishScenario("실패 응답이 최대 시도에 도달하면 BLOCKED로 전환한다",
                    PublishResult.failed("BrokerException"), scenarioSettings.maxAttempts() - 1,
                    OrderConfirmedOutboxStatus.BLOCKED, "BrokerException"),
                new PublishScenario("미확정 응답은 최대 시도 전 PENDING으로 두고 재시도 시각을 기록한다",
                    PublishResult.pending("TimeoutException"), 0,
                    OrderConfirmedOutboxStatus.PENDING, "TimeoutException"),
                new PublishScenario("미확정 응답이 최대 시도에 도달하면 BLOCKED로 전환한다",
                    PublishResult.pending("TimeoutException"), scenarioSettings.maxAttempts() - 1,
                    OrderConfirmedOutboxStatus.BLOCKED, "TimeoutException")
            )
            .map(scenario -> DynamicTest.dynamicTest(scenario.displayName(),
                () -> assertBranch(scenario, scenarioSettings)));
    }

    private void assertBranch(PublishScenario scenario, RelaySettings scenarioSettings) {
        OrderConfirmedOutboxRepository scenarioRepository = mock(OrderConfirmedOutboxRepository.class);
        OutboxPublisher scenarioPublisher = mock(OutboxPublisher.class);
        OrderConfirmedOutbox outbox = outbox("event-branch", NOW);
        outbox.setAttemptCount(scenario.attemptsBefore());
        when(scenarioRepository.findReadyForRelay(NOW, scenarioSettings.batchSize())).thenReturn(List.of(outbox));
        when(scenarioPublisher.publish("order-branch", "payload-branch"))
            .thenReturn(CompletableFuture.completedFuture(scenario.result()));
        OrderConfirmedOutboxRelay scenarioRelay = new OrderConfirmedOutboxRelay(
            scenarioRepository,
            scenarioPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            scenarioSettings.pollInterval(),
            scenarioSettings.batchSize(),
            scenarioSettings.maxAttempts()
        );

        scenarioRelay.pollOnce();

        assertThat(outbox.getAttemptCount()).isEqualTo(scenario.attemptsBefore() + 1);
        assertThat(outbox.getStatus()).isEqualTo(scenario.expectedStatus());
        assertThat(outbox.getLastError()).isEqualTo(scenario.expectedError());
        if (scenario.expectedStatus() == OrderConfirmedOutboxStatus.PUBLISHED) {
            assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
        } else {
            assertThat(outbox.getPublishedAt()).isNull();
        }
        if (scenario.expectedStatus() == OrderConfirmedOutboxStatus.FAILED
            || scenario.expectedStatus() == OrderConfirmedOutboxStatus.PENDING) {
            assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plus(scenarioSettings.pollInterval()));
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

    private record RelaySettings(Duration pollInterval, int batchSize, int maxAttempts) {
    }

    private record PublishScenario(
        String displayName,
        PublishResult result,
        int attemptsBefore,
        OrderConfirmedOutboxStatus expectedStatus,
        String expectedError
    ) {
    }

    @SpringBootConfiguration
    static class TestConfiguration {
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}
