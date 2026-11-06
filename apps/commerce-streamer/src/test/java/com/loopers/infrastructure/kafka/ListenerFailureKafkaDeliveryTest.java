package com.loopers.infrastructure.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.kafka.KafkaLab;
import com.loopers.testcontainers.KafkaBrokerFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 Spring listener의 실패·ack·재시작 정책을 확인한다 (W6 과제 6절, 실험 B). 실험 A(OffsetRedeliveryKafkaDeliveryTest)가
 * "DB 효과 뒤 offset 공백"을 보는 것과 달리, 여기서는 DB commit <b>전</b>의 오류가 listener에서 어떻게 다뤄지는지 본다.
 * handler를 두 번 직접 부른 단위 테스트로는 이 정책(ack 위치, 컨테이너 중단, 재시작 뒤 재전달)을 확인할 수 없다.
 *
 * <p>격리된 topic·group에서 주문 42, 43을 순서대로 보낸 뒤, 42의 두 번째 품목 저장에서 오류를 주입한다.
 * 기대: 완료 기록·첫 품목까지 전체 rollback, 어떤 offset도 commit되지 않음, 43은 건너뛰어 처리되지 않음, listener 중단과 ERROR 로그.
 * 오류를 없애고 같은 group으로 컨테이너를 다시 시작하면 42부터 재전달돼 정상 저장되고 ack(offset commit)된다.
 */
@SpringBootTest
class ListenerFailureKafkaDeliveryTest {

    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-listener-failure", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-failure");
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
        registry.add("order-event.topic-name", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
        registry.add("order-event.listener-auto-startup", () -> "true");
    }

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoSpyBean
    private PurchaseActivityRepository repository;

    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;

    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final ListAppender<ILoggingEvent> listenerLogs = new ListAppender<>();
    private Logger listenerLogger;

    @AfterEach
    void tearDown() {
        if (listenerLogger != null) {
            listenerLogger.detachAppender(listenerLogs);
        }
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        if (container != null && container.isRunning()) {
            container.stop();
        }
        databaseCleanUp.truncateAllTables();
    }

    private static String eventJson(long orderId, long productA, int quantityA, long productB, int quantityB) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"%s","items":[{"productId":%d,"quantity":%d},{"productId":%d,"quantity":%d}]}
            """.formatted(orderId, orderId, Instant.parse("2026-09-07T01:00:00Z"), productA, quantityA, productB, quantityB);
    }

    @DisplayName("DB commit 전 오류가 나면 전체 rollback되고 offset이 진행하지 않으며 listener가 멈추고, 오류를 없애고 같은 group으로 재시작하면 재전달·저장·ack까지 끝난다.")
    @Test
    void stopsOnFailureAndRecoversByRestart() throws Exception {
        // arrange: 오류 로그를 관찰하고, 주문 42의 두 번째 품목 저장에서 실패하도록 주입한다
        listenerLogger = (Logger) LoggerFactory.getLogger(OrderConfirmedListener.class);
        listenerLogs.start();
        listenerLogger.addAppender(listenerLogs);
        doCallRealMethod().doThrow(new IllegalStateException("두 번째 품목 저장 실패(주입)"))
            .when(repository).saveActivity(any());
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        assertThat(container).isNotNull();
        await().atMost(DEADLINE).until(container::isRunning);

        // act: 주문 42, 43을 순서대로 보낸다
        RecordMetadata first;
        RecordMetadata second;
        try (KafkaProducer<String, String> producer = KafkaLab.stringProducer()) {
            first = producer.send(new ProducerRecord<>(TOPIC, "42", eventJson(42, 7, 2, 8, 1))).get(30, TimeUnit.SECONDS);
            second = producer.send(new ProducerRecord<>(TOPIC, "43", eventJson(43, 7, 3, 9, 1))).get(30, TimeUnit.SECONDS);
        }
        TopicPartition partition = new TopicPartition(TOPIC, first.partition());
        assertThat(second.offset()).isEqualTo(first.offset() + 1);

        // assert: 실패한 record에서 listener가 멈춘다
        await().atMost(DEADLINE).until(() -> !container.isRunning());
        assertThat(listenerLogs.list).as("어느 record에서 멈췄는지 남긴 ERROR 로그")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("partition=" + first.partition(), "offset=" + first.offset());
            });

        // assert: 활동·완료 기록 전체가 rollback됐고, 실패 record 이후(43)는 처리되지 않았다
        assertThat(processedRepository.count()).as("완료 기록 rollback").isZero();
        assertThat(activityRepository.count()).as("앞서 저장한 첫 품목까지 rollback").isZero();
        // assert: 어떤 offset도 commit되지 않았다 (실패 record를 건너뛰어 완료 처리하지 않았다)
        assertThat(KafkaLab.committedOffset(GROUP, partition)).as("실패 뒤 committed offset").isNull();

        // act: 오류를 없애고 같은 group으로 컨테이너를 다시 시작한다
        Mockito.reset(repository);
        container.start();

        // assert: 42부터 재전달돼 두 주문이 모두 저장되고, 마지막 record 다음 위치까지 ack(commit)된다
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(processedRepository.count()).isEqualTo(2));
        assertThat(activityRepository.findByOrderIdOrderByProductId(42L)).hasSize(2);
        assertThat(activityRepository.findByOrderIdOrderByProductId(43L)).hasSize(2);
        await().atMost(DEADLINE).untilAsserted(() ->
            assertThat(KafkaLab.committedOffset(GROUP, partition)).isEqualTo(second.offset() + 1));
        assertThat(container.isRunning()).isTrue();
        assertThat(listenerLogs.list).as("재시작 뒤 42가 같은 offset에서 다시 반영됐다")
            .anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("반영 완료", "eventId=order-confirmed:v1:42", "offset=" + first.offset()));
        System.out.printf("[listener 실패·재시작] topic=%s group=%s | 실패 뒤 committed offset=없음 → 재시작 뒤 %d | 활동 행 0 → %d%n",
            TOPIC, GROUP, second.offset() + 1, activityRepository.count());
    }
}
