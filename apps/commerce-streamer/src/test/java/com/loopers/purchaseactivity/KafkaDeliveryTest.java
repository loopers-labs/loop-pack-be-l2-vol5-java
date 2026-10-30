package com.loopers.purchaseactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.application.OrderConfirmedParser;
import com.loopers.purchaseactivity.application.PurchaseActivityHandler;
import com.loopers.purchaseactivity.config.PurchaseActivityKafkaConfiguration;
import com.loopers.purchaseactivity.domain.PurchaseActivityRepository;
import com.loopers.purchaseactivity.infrastructure.ProcessedOrderConfirmationJpaRepository;
import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityEntity;
import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityJpaRepository;
import com.loopers.purchaseactivity.interfaces.OrderConfirmedListener;
import com.loopers.purchaseactivity.support.KafkaDeliveryFixture;
import testsupport.purchaseactivity.PurchaseActivityKafkaTestConfiguration;
import testsupport.purchaseactivity.PurchaseActivityKafkaTestConfiguration.ConsumerStops;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = PurchaseActivityKafkaTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.location=classpath:application-purchase-test.yml",
                "purchase-activity.auto-startup=false", "purchase-activity.poll-timeout-ms=100",
                "purchase-activity.fetch-max-wait-ms=100"})
@ExtendWith(OutputCaptureExtension.class)
class KafkaDeliveryTest {
    private static final KafkaDeliveryFixture LISTENER_KAFKA = new KafkaDeliveryFixture("listener-recovery");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @Autowired private PurchaseActivityHandler handler;
    @Autowired private OrderConfirmedParser parser;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private ConsumerStops consumerStops;
    @Autowired
    @Qualifier(PurchaseActivityKafkaConfiguration.FACTORY)
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;
    @MockitoSpyBean private PurchaseActivityRepository activityStore;
    @MockitoSpyBean private OrderConfirmedListener listener;

    @DynamicPropertySource
    static void kafkaAddress(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", LISTENER_KAFKA::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", LISTENER_KAFKA::bootstrapServers);
        registry.add("order-events.topic", LISTENER_KAFKA::topic);
        registry.add("purchase-activity.group-id", LISTENER_KAFKA::group);
    }

    @AfterEach
    void stopListenerAndCleanDatabase() throws InterruptedException {
        var container = listeners.getListenerContainer(OrderConfirmedListener.ID);
        if (container.isRunning()) {
            var stopped = new CountDownLatch(1);
            container.stop(stopped::countDown);
            assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        }
        activities.deleteAllInBatch();
        confirmations.deleteAllInBatch();
    }

    @AfterAll
    static void cleanKafkaResources() throws Exception {
        LISTENER_KAFKA.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "{\"orderId\":42}", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":2}",
            "{\"eventType\":\"UnknownOrderEvent\",\"schemaVersion\":1,\"eventId\":\"unknown-42\",\"orderId\":42}"
    })
    void 주문_확정_계약에_맞지_않는_메시지는_오프셋을_유지한_채_소비를_멈춘다(String json) throws Exception {
        // Arrange
        var invalid = LISTENER_KAFKA.publishJson("42", json);
        LISTENER_KAFKA.publish(confirmation(43));
        LISTENER_KAFKA.commitStartingOffsetAndCloseConsumer(invalid);
        var container = listeners.getListenerContainer(OrderConfirmedListener.ID);
        consumerStops.clear();
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
        assertThat(LISTENER_KAFKA.committedOffset(invalid)).isEqualTo(invalid.offset());

        // Act
        container.start();
        assertThat(consumerStops.awaitStop(Duration.ofSeconds(20))).isNotNull();

        // Assert
        assertThat(container.isRunning()).isFalse();
        assertThat(LISTENER_KAFKA.committedOffset(invalid)).isEqualTo(invalid.offset());
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
    }

    @Test
    void DB_저장_후_오프셋을_커밋하지_않고_종료하면_같은_그룹이_동일_메시지를_다시_읽는다() throws Exception {
        // Arrange
        try (var kafka = new KafkaDeliveryFixture("redelivery")) {
            var event = confirmation(42);
            var target = kafka.publish(event);
            var firstConsumer = kafka.consumer();
            ConsumerRecord<String, byte[]> firstDelivery;
            try {
                kafka.joinGroupAndCommitStartingOffset(firstConsumer, target);
                assertThat(kafka.committedOffset(target)).isEqualTo(target.offset());
                assertThat(activities.count()).isZero();

                // Act
                firstDelivery = kafka.readOne(firstConsumer);
                handler.consume(parser.parseAndValidate(firstDelivery.key(), firstDelivery.value()));
                assertThat(confirmations.count()).isEqualTo(1);
                assertThat(activities.count()).isEqualTo(2);
                assertThat(kafka.committedOffset(target)).isEqualTo(target.offset());
            } finally {
                firstConsumer.close(Duration.ofSeconds(5));
            }
            var savedBeforeRestart = activities.findByOrderIdOrderByProductId(42).stream()
                    .map(ProductPurchaseActivityEntity::toDomain).toList();
            assertThat(kafka.committedOffset(target)).isEqualTo(target.offset());

            var restartedConsumer = kafka.consumer();
            try {
                restartedConsumer.subscribe(List.of(kafka.topic()));
                var redelivery = kafka.readOne(restartedConsumer);
                handler.consume(parser.parseAndValidate(redelivery.key(), redelivery.value()));
                restartedConsumer.commitSync(Map.of(kafka.partition(target),
                        new OffsetAndMetadata(redelivery.offset() + 1)), Duration.ofSeconds(10));

                // Assert
                assertThat(redelivery.topic()).isEqualTo(firstDelivery.topic());
                assertThat(redelivery.partition()).isEqualTo(firstDelivery.partition());
                assertThat(redelivery.offset()).isEqualTo(firstDelivery.offset()).isEqualTo(target.offset());
                assertThat(activities.findByOrderIdOrderByProductId(42))
                        .extracting(ProductPurchaseActivityEntity::toDomain).containsExactlyElementsOf(savedBeforeRestart);
                assertThat(confirmations.count()).isEqualTo(1);
                assertThat(kafka.committedOffset(target)).isEqualTo(target.offset() + 1);
                System.out.printf("W6_REDELIVERY topic=%s partition=%d offset=%d deliveries=2 activities=%d "
                                + "committed=%d -> %d -> %d%n", target.topic(), target.partition(), target.offset(),
                        activities.count(), target.offset(), target.offset(), kafka.committedOffset(target));
            } finally {
                restartedConsumer.close(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void listener의_DB_실패는_전체_롤백과_소비_중단으로_남고_복구_후_같은_메시지를_처리한다(
            CapturedOutput output) throws Exception {
        // Arrange
        var event = confirmation(43);
        var target = LISTENER_KAFKA.publish(event);
        LISTENER_KAFKA.commitStartingOffsetAndCloseConsumer(target);
        var container = listeners.getListenerContainer(OrderConfirmedListener.ID);
        var failedWrite = new CountDownLatch(1);
        var deliveries = new CopyOnWriteArrayList<ConsumerRecord<String, byte[]>>();
        doAnswer(invocation -> {
            deliveries.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(listener).listen(any(), any());
        doAnswer(invocation -> {
            failedWrite.countDown();
            throw new DataAccessResourceFailureException("W6 second item DB failure");
        }).when(activityStore).save(argThat(activity -> activity.productId() == 8));
        consumerStops.clear();
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
        assertThat(LISTENER_KAFKA.committedOffset(target)).isEqualTo(target.offset());
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Act
        container.start();
        assertThat(failedWrite.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(consumerStops.awaitStop(Duration.ofSeconds(20))).isNotNull();

        // Assert
        assertThat(container.isRunning()).isFalse();
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
        assertThat(LISTENER_KAFKA.committedOffset(target)).isEqualTo(target.offset());
        assertThat(output.getAll()).contains("W6 second item DB failure");
        assertThat(deliveries).hasSize(1);

        // Act: 원인만 제거하고 같은 listener와 group을 다시 시작한다.
        doCallRealMethod().when(activityStore).save(any());
        container.start();

        // Assert
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(activities.count()).isEqualTo(2);
            assertThat(confirmations.count()).isEqualTo(1);
            assertThat(LISTENER_KAFKA.committedOffset(target)).isEqualTo(target.offset() + 1);
        });
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo(target.topic());
            assertThat(record.partition()).isEqualTo(target.partition());
            assertThat(record.offset()).isEqualTo(target.offset());
        });
        assertThat(activities.findByOrderIdOrderByProductId(event.orderId()))
                .extracting(row -> row.toDomain().quantity()).containsExactly(2, 1);
        System.out.printf("W6_LISTENER_RECOVERY topic=%s group=%s partition=%d offset=%d "
                        + "stoppedOffset=%d recoveredOffset=%d activities=%d%n", target.topic(), LISTENER_KAFKA.group(),
                target.partition(), target.offset(), target.offset(), LISTENER_KAFKA.committedOffset(target), activities.count());
    }

    private static OrderConfirmed confirmation(long orderId) {
        return OrderConfirmed.confirmed(orderId, CONFIRMED_AT,
                List.of(new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(8, 1)));
    }
}
