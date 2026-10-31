package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DefaultBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class KafkaDbRetryPolicyExperimentTest extends KafkaPurchaseExperimentSupport {
    @MockitoSpyBean private PurchaseActivityRepositoryImpl repository;

    @ParameterizedTest(name = "selected DB retry policy: {0}")
    @EnumSource(Failure.class)
    @Timeout(100)
    void retriesAfterOneAndTwoSecondsThenSucceedsOrStopsWithoutSkipping(Failure failure) throws Exception {
        String topic = "selected-db-retry-" + UUID.randomUUID();
        String group = "selected-db-retry-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        var faultActive = new AtomicBoolean(true);
        var saves = new AtomicInteger();
        var exhausted = new AtomicInteger();
        var firstFailure = new CountDownLatch(1);
        var inspection = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        List<Long> configuredWaits = new CopyOnWriteArrayList<>();
        List<Long> starts = new CopyOnWriteArrayList<>();
        List<Long> failures = new CopyOnWriteArrayList<>();
        List<String> deliveries = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (activity.getOrderId().equals(42L) && activity.getProductId().equals(8L)) {
                int attempt = saves.incrementAndGet();
                if (faultActive.get() && (failure == Failure.PERSISTENT || attempt <= 2)) {
                    throw new TransientDataAccessResourceException("experiment: DB save unavailable");
                }
            }
            return invocation.callRealMethod();
        }).when(repository).save(any());

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            assertThat(sendOrder(producer, topic, 43L).offset()).isEqualTo(1);
            var container = container(topic, group, record -> {
                deliveries.add(record.key() + ":" + record.offset());
                if (record.key().equals("42")) {
                    starts.add(System.nanoTime());
                }
                try {
                    adapter.handle(record);
                } catch (RuntimeException exception) {
                    if (firstFailure.getCount() > 0) {
                        firstFailure.countDown();
                        releaseAfterInspection(inspection);
                    }
                    failures.add(System.nanoTime());
                    throw exception;
                }
            }, stopped);
            var exponential = new ExponentialBackOffWithMaxRetries(2);
            exponential.setInitialInterval(1_000L);
            exponential.setMultiplier(2.0);
            exponential.setMaxInterval(2_000L);
            var actualWaiting = new DefaultBackOffHandler() {
                @Override
                public void onNextBackOff(MessageListenerContainer listenerContainer, Exception exception,
                                          long interval) {
                    configuredWaits.add(interval);
                    super.onNextBackOff(listenerContainer, exception, interval);
                }
            };
            var stopping = new CommonContainerStoppingErrorHandler();
            ConsumerAwareRecordRecoverer stopAfterRetries = (record, consumer, exception) -> {
                exhausted.incrementAndGet();
                stopping.handleRemaining(exception, List.of(record), consumer, container);
            };
            container.setCommonErrorHandler(new DefaultErrorHandler(stopAfterRetries, exponential, actualWaiting));
            try {
                container.start();
                assertThat(firstFailure.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(activities.count()).isZero();
                assertThat(confirmations.count()).isZero();
                assertThat(committed(admin, group, partition)).isZero();
                inspection.countDown();

                if (failure == Failure.PERSISTENT) {
                    assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
                    assertThat(container.isRunning()).isFalse();
                    assertThat(deliveries).containsExactly("42:0", "42:0", "42:0");
                    assertThat(configuredWaits).containsExactly(1_000L, 2_000L);
                    assertThat(exhausted.get()).isEqualTo(1);
                    assertThat(committed(admin, group, partition)).isZero();
                    assertThat(activities.count()).isZero();
                    assertThat(confirmations.count()).isZero();
                    faultActive.set(false);
                    container.start();
                }
                await(() -> committed(admin, group, partition) == 2L && confirmations.count() == 2);
                assertOrders(42L, 43L);
                if (failure == Failure.RECOVERS_ON_THIRD_ATTEMPT) {
                    assertThat(deliveries).containsExactly("42:0", "42:0", "42:0", "43:1");
                    assertThat(configuredWaits).containsExactly(1_000L, 2_000L);
                    assertThat(exhausted.get()).isZero();
                } else {
                    assertThat(deliveries).containsExactly("42:0", "42:0", "42:0", "42:0", "43:1");
                }
                List<Long> actualWaits = List.of(
                    TimeUnit.NANOSECONDS.toMillis(starts.get(1) - failures.get(0)),
                    TimeUnit.NANOSECONDS.toMillis(starts.get(2) - failures.get(1)));
                // 스케줄러·poll 비용에는 상한을 두지 않는다. 실제 대기도 함께 관찰한다.
                assertThat(actualWaits.get(0)).isGreaterThanOrEqualTo(900L);
                assertThat(actualWaits.get(1)).isGreaterThanOrEqualTo(1_900L);
                report("SELECTED_DB_RETRY_EXPERIMENT", Map.of("failure", failure.name(),
                    "configuredWaitsMs", configuredWaits, "observedWaitsMs", actualWaits,
                    "deliveries", deliveries, "stopsOnExhaustion", exhausted.get(),
                    "finalCommittedOffset", committed(admin, group, partition),
                    "confirmationRows", confirmations.count(), "activityRows", activities.count()));
            } finally {
                inspection.countDown();
                container.stop();
            }
        }
    }

    enum Failure { RECOVERS_ON_THIRD_ATTEMPT, PERSISTENT }
}
