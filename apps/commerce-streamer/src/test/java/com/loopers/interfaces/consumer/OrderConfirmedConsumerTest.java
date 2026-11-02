package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.facade.PurchaseActivityFacade;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 6주차 설계 5 · 7.4 의 실제 Spring listener 경로. 실제 broker 의 committed offset 과 DB 결과로 판정함.
 * 다른 테스트 컨텍스트의 listener 와 섞이지 않게 이 클래스만의 topic · group 을 씀. 재시도 시간은 짧게 줄임
 */
@SpringBootTest(properties = {
    "order-event.topic=order-events-consumer-test",
    "order-event.consumer-group=purchase-activity-consumer-test",
    "order-event.retry.initial-interval=100ms",
    "order-event.retry.max-interval=200ms",
    "order-event.retry.max-elapsed=1500ms"
})
class OrderConfirmedConsumerTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private KafkaTemplate<String, byte[]> orderConfirmedDltTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedListenerContainerFactory;

    @MockitoSpyBean
    private PurchaseActivityFacade purchaseActivityFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${order-event.topic}")
    private String topic;

    @Value("${order-event.consumer-group}")
    private String group;

    @AfterEach
    void tearDown() {
        if (!container().isChildRunning()) {
            restart();
        }
        databaseCleanUp.truncateAllTables();
    }

    /** 중단 handler 는 child 컨테이너를 멈추므로 부모의 isRunning 이 아니라 child 상태를 봄 */
    private ConcurrentMessageListenerContainer<?, ?> container() {
        return (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);
    }

    /** 부모는 실행 중으로 남아 start 가 무시되므로 멈춘 뒤 다시 시작함. 운영의 재시작과 같은 효과 */
    private void restart() {
        container().stop();
        container().start();
    }

    private static String event(long orderId, int quantity) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"2026-09-07T01:00:00.123456Z","items":[{"productId":7,"quantity":%d}]}
            """.formatted(orderId, orderId, quantity);
    }

    private RecordMetadata send(long orderId, String value) throws Exception {
        return orderConfirmedDltTemplate.send(topic, String.valueOf(orderId), value.getBytes(StandardCharsets.UTF_8))
            .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    /** 이 group 이 다음에 읽을 위치. 아직 commit 이 없으면 -1 */
    private long committedOffset(RecordMetadata metadata) {
        TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(partition);
            return committed == null ? -1 : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(100);
        }
    }

    private long activityCount(long orderId) {
        return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(
            "select count(*) from product_purchase_activity where order_id = " + orderId).getSingleResult()).longValue());
    }

    private ConsumerRecord<String, byte[]> dltRecordOf(RecordMetadata original) throws InterruptedException {
        TopicPartition dlt = new TopicPartition(topic + ".DLT", original.partition());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.kafka.bootstrap-servers"),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(List.of(dlt));
            consumer.seekToBeginning(List.of(dlt));
            Instant deadline = Instant.now().plus(WAIT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    Header header = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
                    if (header != null && ByteBuffer.wrap(header.value()).getLong() == original.offset()) {
                        return record;
                    }
                }
            }
            throw new AssertionError("DLT 에서 원래 offset " + original.offset() + " 의 record 를 찾지 못함");
        }
    }

    @DisplayName("정상 메시지면, ")
    @Nested
    class Success {
        @DisplayName("구매 활동을 저장한 뒤 그 record 다음 위치를 commit 한다.")
        @Test
        void savesThenCommitsNext() throws Exception {
            // act
            RecordMetadata sent = send(1001L, event(1001L, 2));

            // assert
            waitUntil("committed offset 이 n+1", () -> committedOffset(sent) == sent.offset() + 1);
            assertThat(activityCount(1001L)).isEqualTo(1);
        }

        @DisplayName("같은 메시지가 두 번 와도 활동은 하나이고 offset 은 두 record 를 모두 지난다.")
        @Test
        void isNoOp_whenRedelivered() throws Exception {
            // act
            send(1002L, event(1002L, 2));
            RecordMetadata second = send(1002L, event(1002L, 2));

            // assert
            waitUntil("두 번째 record 까지 commit", () -> committedOffset(second) == second.offset() + 1);
            assertThat(activityCount(1002L)).isEqualTo(1);
        }
    }

    @DisplayName("영구 오류면, ")
    @Nested
    class PermanentFailure {
        @DisplayName("재시도 없이 원래 바이트를 같은 partition 의 DLT 로 옮기고 offset 을 진행한다. 활동은 없다.")
        @Test
        void movesToDlt_whenBrokenMessage() throws Exception {
            // act
            RecordMetadata sent = send(2001L, "not-json");

            // assert
            waitUntil("DLT 뒤 offset 진행", () -> committedOffset(sent) == sent.offset() + 1);
            ConsumerRecord<String, byte[]> moved = dltRecordOf(sent);
            assertAll(
                () -> assertThat(moved.partition()).isEqualTo(sent.partition()),
                () -> assertThat(moved.key()).isEqualTo("2001"),
                () -> assertThat(new String(moved.value(), StandardCharsets.UTF_8)).isEqualTo("not-json"),
                () -> assertThat(activityCount(2001L)).isZero()
            );
        }

        @DisplayName("같은 eventId 에 다른 수량이 오면 첫 사실은 그대로 두고 두 번째를 DLT 로 옮긴다.")
        @Test
        void movesConflictToDlt() throws Exception {
            // act
            send(2002L, event(2002L, 2));
            RecordMetadata conflict = send(2002L, event(2002L, 5));

            // assert
            waitUntil("충돌 record 까지 진행", () -> committedOffset(conflict) == conflict.offset() + 1);
            dltRecordOf(conflict);
            long quantity = transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(
                "select quantity from product_purchase_activity where order_id = 2002").getSingleResult()).longValue());
            assertThat(quantity).isEqualTo(2);
        }
    }

    @DisplayName("일시적 오류면, ")
    @Nested
    class TransientFailure {
        @DisplayName("재시도하는 동안 offset 은 그 record 에 머물고, 회복되면 저장한 뒤 진행한다.")
        @Test
        void retriesThenSaves() throws Exception {
            // arrange
            AtomicInteger failuresLeft = new AtomicInteger(3);
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                attempts.incrementAndGet();
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new TransientDataAccessResourceException("주입한 DB 일시 오류");
                }
                return invocation.callRealMethod();
            }).when(purchaseActivityFacade).record(any());

            // act
            RecordMetadata sent = send(3001L, event(3001L, 1));
            waitUntil("첫 실패 관찰", () -> attempts.get() >= 1);
            long duringRetry = committedOffset(sent);

            // assert
            waitUntil("회복 뒤 진행", () -> committedOffset(sent) == sent.offset() + 1);
            assertAll(
                () -> assertThat(duringRetry).isLessThanOrEqualTo(sent.offset()),
                () -> assertThat(attempts.get()).isEqualTo(4),
                () -> assertThat(activityCount(3001L)).isEqualTo(1)
            );
        }

        @DisplayName("상한을 넘기면 commit 없이 소비를 멈추고, 고친 뒤 재시작하면 같은 record 부터 다시 받아 저장한다.")
        @Test
        void stopsThenRedeliversAfterRestart() throws Exception {
            // arrange
            AtomicInteger failing = new AtomicInteger(1);
            doAnswer(invocation -> {
                if (failing.get() == 1) {
                    throw new TransientDataAccessResourceException("주입한 DB 장애");
                }
                return invocation.callRealMethod();
            }).when(purchaseActivityFacade).record(any());

            // act
            RecordMetadata sent = send(3002L, event(3002L, 1));
            waitUntil("상한 뒤 중단", () -> !container().isChildRunning());
            boolean expectedState = container().isInExpectedState();
            long whileStopped = committedOffset(sent);
            long activityWhileStopped = activityCount(3002L);
            failing.set(0);
            restart();

            // assert
            waitUntil("재시작 뒤 재전달 · 저장 · ack", () -> committedOffset(sent) == sent.offset() + 1);
            assertAll(
                () -> assertThat(whileStopped).isLessThanOrEqualTo(sent.offset()),
                () -> assertThat(activityWhileStopped).isZero(),
                // 비정상 중단이라 운영에서 이상 상태로 알아볼 수 있음
                () -> assertThat(expectedState).isFalse(),
                () -> assertThat(activityCount(3002L)).isEqualTo(1)
            );
        }
    }

    @DisplayName("listener 는 표기가 아니라 실제 설정으로 auto commit 끔 · earliest · MANUAL_IMMEDIATE · concurrency 1 을 받는다. (설계 5.1)")
    @Test
    void receivesEffectiveSettings() {
        Map<String, Object> config = orderConfirmedListenerContainerFactory.getConsumerFactory().getConfigurationProperties();
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);

        assertAll(
            () -> assertThat(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false),
            () -> assertThat(config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest"),
            () -> assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE),
            () -> assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(1),
            () -> assertThat(container.getGroupId()).isEqualTo(group)
        );
    }
}
