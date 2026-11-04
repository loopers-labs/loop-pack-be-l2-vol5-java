package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaListener;
import com.loopers.interfaces.consumer.OrderConfirmedRecordAdapter;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 실제 BATCH @KafkaListener의 정상·중복·미커밋 재전달. 중단 hook에는 테스트 전용 오류 처리기를 쓴다. */
@SpringBootTest(classes = {KafkaFailureHandlingExperimentTest.DatabaseConfig.class,
    OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaListener.class,
    OrderConfirmedKafkaListenerIntegrationTest.TestErrors.class}, properties = {
        "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.concurrency=1",
        "commerce.order-events.consumer.auto-startup=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedKafkaListenerIntegrationTest extends KafkaPurchaseExperimentSupport {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "actual-order-listener-" + UUID.randomUUID();
    @Autowired private KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean private OrderConfirmedRecordAdapter listenerAdapter;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        properties.add("commerce.order-events.topic", () -> TOPIC);
    }

    @Override
    @BeforeAll
    void startExperimentBroker() {
        broker = BROKER;
    }

    @AfterAll
    void stopSubscriptions() {
        registry.stop();
    }

    @Test
    @Timeout(100)
    void actualBatchSubscriptionCommitsAfterPurchaseAndReplaysCommittedFactsWithoutNewRows() throws Exception {
        String group = "purchase-activity";
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        var container = registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.BATCH);
        var stopOnce = new AtomicBoolean(true);
        var afterDb = new CountDownLatch(1);
        var permitStop = new CountDownLatch(1);
        var transactionActive = new AtomicBoolean(true);
        List<Long> replayedOffsets = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            ConsumerRecord<String, byte[]> record = invocation.getArgument(0);
            if (record.offset() == 2L) {
                replayedOffsets.add(record.offset());
                if (stopOnce.compareAndSet(true, false)) {
                    transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                    afterDb.countDown();
                    releaseAfterInspection(permitStop);
                    throw new IllegalStateException("test: stop after DB commit before BATCH listener returns");
                }
            }
            return null;
        }).when(listenerAdapter).handle(any(ConsumerRecord.class));
        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            try {
                container.start();
                assertThat(sendOrder(producer, TOPIC, 42L).offset()).isZero();
                await(() -> committed(admin, group, partition) == 1L);
                assertOrders(42L);
                List<Long> rowIds = activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList();
                Long confirmationId = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId();

                assertThat(sendOrder(producer, TOPIC, 42L).offset()).isEqualTo(1);
                await(() -> committed(admin, group, partition) == 2L);
                assertOrders(42L);
                assertThat(activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList())
                    .isEqualTo(rowIds);
                assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId())
                    .isEqualTo(confirmationId);
                assertThat(sendOrder(producer, TOPIC, 43L).offset()).isEqualTo(2L);
                assertThat(afterDb.await(25, TimeUnit.SECONDS)).isTrue();
                assertThat(transactionActive).isFalse();
                assertOrders(42L, 43L);
                assertThat(committed(admin, group, partition)).isEqualTo(2L);
                List<Long> allRowIds = activities.findAll().stream()
                    .map(ProductPurchaseActivity::getId).sorted().toList();
                Long secondConfirmationId = confirmations.findByEventId("order-confirmed:v1:43")
                    .orElseThrow().getId();
                permitStop.countDown();
                await(() -> !container.isRunning());
                container.stop();
                assertThat(committed(admin, group, partition)).isEqualTo(2L);
                assertOrders(42L, 43L);

                container.start();
                await(() -> committed(admin, group, partition) == 3L && replayedOffsets.size() == 2);
                assertThat(replayedOffsets).containsExactly(2L, 2L);
                assertOrders(42L, 43L);
                assertThat(activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList())
                    .isEqualTo(allRowIds);
                assertThat(confirmations.findByEventId("order-confirmed:v1:43").orElseThrow().getId())
                    .isEqualTo(secondConfirmationId);
                assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId())
                    .isEqualTo(confirmationId);
                report("ACTUAL_ORDER_LISTENER_INTEGRATION", Map.of("group", group, "ackMode", "BATCH",
                    "listenerId", OrderConfirmedKafkaListener.ID, "committedOffsets", List.of(0, 1, 2, 2, 2, 3),
                    "replayedOffsets", replayedOffsets, "confirmationRows", confirmations.count(),
                    "activityRows", activities.count(), "rowIdsUnchanged", true));
            } finally {
                permitStop.countDown();
                container.stop();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestErrors {
        @Bean(name = OrderConfirmedKafkaConfiguration.ERROR_HANDLER)
        CommonErrorHandler testOnlyErrorHandler() {
            return new CommonContainerStoppingErrorHandler();
        }
    }
}
