package com.loopers.interfaces.consumer;

import com.loopers.domain.activity.ProductPurchaseActivityModel;
import com.loopers.domain.activity.PurchaseActivityRepository;
import com.loopers.infrastructure.activity.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.activity.ProductPurchaseActivityJpaRepository;
import com.loopers.support.OrderConfirmedJson;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.loopers.support.KafkaTestSupport.await;
import static com.loopers.support.KafkaTestSupport.committedOffset;
import static com.loopers.support.KafkaTestSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 실제 Spring listener(OrderConfirmedConsumer)의 ack·실패 정책. 실제 broker·MySQL, 이 클래스 전용 topic(partition 1)·group.
 * 기대: DB commit 뒤에만 ack, DB 오류는 1초 간격 2회 재시도 후 컨테이너 중단, 실패 record 의 offset 은 commit 되지 않는다(n → n),
 * 원인 제거 후 같은 group 으로 재시작하면 같은 record 를 다시 받아 저장하고 n+1 을 commit 한다.
 */
@SpringBootTest(properties = {
    "kafka.topics.order-events.name=" + OrderConfirmedConsumerTest.TOPIC,
    "kafka.consumers.purchase-activity.group-id=" + OrderConfirmedConsumerTest.GROUP
})
@Import(OrderConfirmedConsumerTest.TopicConfig.class)
class OrderConfirmedConsumerTest {
    static final String TOPIC = "order-events-consumer-test";
    static final String GROUP = "purchase-activity-consumer-test";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedConsumerTest.class);

    @TestConfiguration
    static class TopicConfig {
        @Bean
        NewTopic consumerTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;
    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);
        if (!isConsuming()) {
            container.stop();
            container.start();
        }
        await("listener 가 partition 을 배정받음", () -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private boolean isConsuming() {
        ConcurrentMessageListenerContainer<?, ?> container =
            (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);
        return container.isRunning() && !container.getContainers().isEmpty()
            && container.getContainers().stream().allMatch(MessageListenerContainer::isRunning);
    }

    private boolean hasActivities(long orderId) {
        return !activityRepository.findByOrderIdOrderByProductIdAsc(orderId).isEmpty();
    }

    @DisplayName("구매 활동 DB commit 뒤에 ack 한다: 저장된 활동과 group 의 committed offset = 대상 offset + 1.")
    @Test
    void acksAfterDbCommit() {
        RecordMetadata sent = send(TOPIC, null, "42", OrderConfirmedJson.of(42, 7, 2, 8, 1));

        await("주문 42 구매 활동 저장", () -> hasActivities(42));
        await("committed offset = n + 1", () -> Long.valueOf(sent.offset() + 1).equals(committedOffset(GROUP, PARTITION)));

        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(42L))
            .extracting(ProductPurchaseActivityModel::getProductId, ProductPurchaseActivityModel::getQuantity)
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
    }

    @DisplayName("DB commit 전 오류: 전체 rollback, 2회 재시도 뒤 컨테이너 중단, offset 은 n 그대로. 원인 제거 후 재시작하면 같은 record 를 저장하고 n+1 을 commit 한다.")
    @Test
    void dbFailure_stopsWithoutCommit_thenRedeliversAfterRestart() {
        RecordMetadata warmUp = send(TOPIC, null, "49", OrderConfirmedJson.of(49, 7, 1));
        await("앞선 record 까지 commit", () -> Long.valueOf(warmUp.offset() + 1).equals(committedOffset(GROUP, PARTITION)));
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            ProductPurchaseActivityModel activity = invocation.getArgument(0);
            if (activity.getOrderId() == 50L && activity.getProductId() == 8L) {
                attempts.incrementAndGet();
                if (failing.get()) {
                    throw new QueryTimeoutException("주입된 DB 오류: 두 번째 품목 저장 중");
                }
            }
            return invocation.callRealMethod();
        }).when(purchaseActivityRepository).saveActivity(any());

        RecordMetadata target = send(TOPIC, null, "50", OrderConfirmedJson.of(50, 7, 2, 8, 1));
        long n = target.offset();
        assertThat(n).isEqualTo(warmUp.offset() + 1);

        await("재시도 소진 뒤 컨테이너 중단", () -> !isConsuming());
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(hasActivities(50)).isFalse();
        assertThat(processedRepository.findByEventIdOrOrderId("order-confirmed:v1:50", 50L)).isEmpty();
        assertThat(committedOffset(GROUP, PARTITION)).isEqualTo(n);
        Long committedWhileStopped = committedOffset(GROUP, PARTITION);
        int attemptsBeforeRestart = attempts.get();

        failing.set(false);
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);
        container.stop();
        container.start();

        await("재시작 뒤 같은 record 재전달·저장", () -> hasActivities(50));
        await("committed offset = n + 1", () -> Long.valueOf(n + 1).equals(committedOffset(GROUP, PARTITION)));
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(50L)).hasSize(2);

        log.info("[W6 관찰: listener 실패 경로] target={}-{}@{}, 앞선 committed={}, 중단 중 committed={}, 중단 전 시도={}회, 재시작 뒤 committed={}, 총 시도={}회, 활동(order 50)={}행",
            target.topic(), target.partition(), n, warmUp.offset() + 1, committedWhileStopped, attemptsBeforeRestart,
            committedOffset(GROUP, PARTITION), attempts.get(), activityRepository.findByOrderIdOrderByProductIdAsc(50L).size());
    }
}
