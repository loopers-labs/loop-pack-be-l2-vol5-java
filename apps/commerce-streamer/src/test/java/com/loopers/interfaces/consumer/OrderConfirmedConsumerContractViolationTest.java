package com.loopers.interfaces.consumer;

import com.loopers.application.activity.PurchaseActivityCommand;
import com.loopers.application.activity.PurchaseActivityFacade;
import com.loopers.domain.activity.ProductPurchaseActivityModel;
import com.loopers.infrastructure.activity.ProductPurchaseActivityJpaRepository;
import com.loopers.support.OrderConfirmedJson;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.loopers.support.KafkaTestSupport.await;
import static com.loopers.support.KafkaTestSupport.committedOffset;
import static com.loopers.support.KafkaTestSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 계약 위반 record(같은 eventId 의 다른 수량)는 재시도하지 않고 바로 컨테이너를 멈춘다. 추가 구매도, offset 진행도 없다.
 * 실패 record 가 topic 에 남아 컨테이너는 멈춘 채로 끝나므로 전용 topic·group 을 쓰고 컨텍스트를 닫는다.
 */
@SpringBootTest(properties = {
    "kafka.topics.order-events.name=" + OrderConfirmedConsumerContractViolationTest.TOPIC,
    "kafka.consumers.purchase-activity.group-id=" + OrderConfirmedConsumerContractViolationTest.GROUP
})
@Import(OrderConfirmedConsumerContractViolationTest.TopicConfig.class)
@DirtiesContext
class OrderConfirmedConsumerContractViolationTest {
    static final String TOPIC = "order-events-contract-test";
    static final String GROUP = "purchase-activity-contract-test";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @TestConfiguration
    static class TopicConfig {
        @Bean
        NewTopic contractTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean
    private PurchaseActivityFacade purchaseActivityFacade;
    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

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

    @DisplayName("같은 eventId 로 수량이 다른 record 가 오면 한 번만 시도하고 컨테이너를 멈춘다. 활동은 처음 값 그대로, offset 은 실패 record 에 머문다.")
    @Test
    void conflictingRecord_stopsImmediately_withoutCommit() {
        RecordMetadata first = send(TOPIC, null, "60", OrderConfirmedJson.of(60, 7, 2));
        await("첫 사실 commit", () -> Long.valueOf(first.offset() + 1).equals(committedOffset(GROUP, PARTITION)));

        RecordMetadata conflicting = send(TOPIC, null, "60", OrderConfirmedJson.of(60, 7, 9));

        await("계약 위반으로 컨테이너 중단", () -> !isConsuming());
        verify(purchaseActivityFacade, times(2)).consume(argThat((PurchaseActivityCommand command) -> command.orderId() == 60L));
        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(60L))
            .extracting(ProductPurchaseActivityModel::getProductId, ProductPurchaseActivityModel::getQuantity)
            .containsExactly(tuple(7L, 2));
        assertThat(committedOffset(GROUP, PARTITION)).isEqualTo(conflicting.offset());
    }
}
