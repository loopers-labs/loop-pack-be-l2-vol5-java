package com.loopers.infrastructure.kafka;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.PurchaseActivityRecorder;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedParser;
import com.loopers.support.kafka.KafkaLab;
import com.loopers.support.kafka.RecordingConsumer;
import com.loopers.testcontainers.KafkaBrokerFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB commit까지 끝났지만 offset을 commit하지 못한 record가 같은 group에서 다시 전달되는 것을 관찰한다 (W6 과제 6절, 실험 A).
 * 실제 OrderConfirmed 한 건을 실제 파서·handler·DB로 처리하고, 첫 consumer의 offset commit만 보류한다.
 *
 * <p>관찰하려는 값은 전달 2회, 같은 record 위치, 한 주문의 활동 행 수·수량 불변, committed offset n → n → n+1이다.
 * offset n은 임의로 정하지 않고 발행 metadata에서 얻는다. 두 번째 consumer는 seek·offset reset으로 되감지 않고,
 * 처음 저장한 group offset에서 실제로 다시 읽는다({@code auto.offset.reset=none}이라 commit된 위치가 없었다면 오류가 난다).
 *
 * <p>단일 broker와 통제된 종료(consumer.close)로 만든 상황이다. 프로세스를 강제 종료한 것이 아니다.
 */
@SpringBootTest(properties = "order-event.listener-auto-startup=false")
class OffsetRedeliveryKafkaDeliveryTest {

    private static final Duration DEADLINE = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
    }

    @Autowired
    private OrderConfirmedParser parser;

    @Autowired
    private PurchaseActivityHandler handler;

    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;

    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    // 실패한 단언으로 중간에 멈춰도 이 테스트가 연 consumer를 닫는다 (이미 닫은 consumer를 다시 닫아도 안전하다)
    private final List<RecordingConsumer> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(consumer -> consumer.close(Duration.ofSeconds(5)));
        databaseCleanUp.truncateAllTables();
    }

    private static String eventJson(long orderId, long productA, int quantityA, long productB, int quantityB) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"%s","items":[{"productId":%d,"quantity":%d},{"productId":%d,"quantity":%d}]}
            """.formatted(orderId, orderId, Instant.parse("2026-09-07T01:00:00Z"), productA, quantityA, productB, quantityB);
    }

    private static Map<String, Object> bytesConsumerProps(String group, String clientId, String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>(KafkaLab.consumerProps(group, clientId, autoOffsetReset));
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return props;
    }

    /** 대상 record(partition·offset)가 올 때까지 poll한다. 시한이 지나면 실패한다. */
    private static ConsumerRecord<String, byte[]> pollUntil(RecordingConsumer consumer, TopicPartition target, long offset) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(300))) {
                if (record.partition() == target.partition() && record.offset() == offset) {
                    return record;
                }
            }
        }
        throw new AssertionError("시한 안에 record를 받지 못했습니다: " + target + "@" + offset);
    }

    @DisplayName("DB commit 뒤 offset을 commit하지 못하고 consumer가 종료돼도, 같은 group이 같은 위치에서 record를 다시 받고 구매 효과는 한 번뿐이다.")
    @Test
    void redeliversSameRecord_afterDbCommitWithoutOffsetCommit() throws Exception {
        // arrange: 격리 topic에 선행 record 하나와 대상 record(주문 42)를 보낸다. 대상의 위치(n)는 broker가 알려 준 metadata로 안다
        String topic = KafkaBrokerFixture.createTopic("order-events-redelivery", 1);
        String group = KafkaBrokerFixture.uniqueName("redelivery-group");
        RecordMetadata targetMetadata;
        try (KafkaProducer<String, String> producer = KafkaLab.stringProducer()) {
            producer.send(new ProducerRecord<>(topic, "41", eventJson(41, 5, 1, 6, 1))).get(30, TimeUnit.SECONDS);
            targetMetadata = producer.send(new ProducerRecord<>(topic, "42", eventJson(42, 7, 2, 8, 1))).get(30, TimeUnit.SECONDS);
        }
        TopicPartition partition = new TopicPartition(topic, targetMetadata.partition());
        long n = targetMetadata.offset();
        assertThat(n).as("선행 record가 있어 대상은 첫 record가 아니다").isGreaterThan(0);

        // act 1: 첫 consumer가 구독·배정된 뒤, 대상의 위치 n을 commit하고 n에서 읽는다 (재전달이 아니라 실험의 시작 위치 준비)
        Map<String, Object> firstProps = bytesConsumerProps(group, "redelivery-first", "earliest");
        assertThat(firstProps.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).as("자동 commit 끔").isEqualTo(false);
        RecordingConsumer first = new RecordingConsumer(firstProps);
        opened.add(first);
        first.subscribe(List.of(topic));
        long assignDeadline = System.nanoTime() + DEADLINE.toNanos();
        while (first.assignment().isEmpty() && System.nanoTime() < assignDeadline) {
            first.poll(Duration.ofMillis(300));
        }
        assertThat(first.assignment()).as("첫 consumer가 partition을 배정받았다").contains(partition);
        first.commitSync(Map.of(partition, new OffsetAndMetadata(n)));
        first.seek(partition, n);
        assertThat(KafkaLab.committedOffset(group, partition)).as("시작 위치").isEqualTo(n);

        // act 2: 대상 record를 실제 파서·handler·DB로 처리한다. DB는 commit되지만 offset(n+1)은 commit하지 않는다
        ConsumerRecord<String, byte[]> firstDelivery = pollUntil(first, partition, n);
        PurchaseActivityRecorder.Result firstResult = handler.consume(parser.parseAndValidate(firstDelivery.key(), firstDelivery.value()));

        // assert: DB 효과는 있고, broker의 committed offset은 여전히 n이다
        assertThat(firstResult).isEqualTo(PurchaseActivityRecorder.Result.RECORDED);
        assertThat(activityRepository.findByOrderIdOrderByProductId(42L)).hasSize(2);
        assertThat(processedRepository.findByEventId("order-confirmed:v1:42")).isPresent();
        assertThat(KafkaLab.committedOffset(group, partition)).as("처리 뒤에도 committed offset").isEqualTo(n);

        // act 3: offset commit 없이 종료한다 (자동 commit이 꺼져 있고, close도 대기 중 commit을 하지 않는다)
        first.close(Duration.ofSeconds(10));

        // assert: 호출 이력상 commit은 시작 위치 준비의 한 번(n)뿐이고, n+1을 요청한 적이 없다
        assertThat(first.explicitCommits()).containsExactly(Map.of(partition, n));
        assertThat(first.otherCommitCalls()).as("위치를 지정하지 않은 commit·비동기 commit 호출").isZero();
        assertThat(KafkaLab.committedOffset(group, partition)).as("종료 뒤 committed offset").isEqualTo(n);

        // act 4: 같은 group의 새 consumer가 구독만 한다. seek·offset 재설정 없이 처음 저장한 group offset에서 읽는다
        RecordingConsumer second = new RecordingConsumer(bytesConsumerProps(group, "redelivery-second", "none"));
        opened.add(second);
        ConsumerRecord<String, byte[]> secondDelivery;
        PurchaseActivityRecorder.Result secondResult;
        try {
            second.subscribe(List.of(topic));
            secondDelivery = pollUntil(second, partition, n);
            secondResult = handler.consume(parser.parseAndValidate(secondDelivery.key(), secondDelivery.value()));

            // assert: 같은 record가 다시 전달됐다
            assertThat(second.seekCalls()).as("되감기 없이 실제로 다시 읽었다").isZero();
            assertThat(second.explicitCommits()).isEmpty();
            assertThat(secondDelivery.partition()).isEqualTo(firstDelivery.partition());
            assertThat(secondDelivery.offset()).isEqualTo(firstDelivery.offset());
            assertThat(secondDelivery.key()).isEqualTo(firstDelivery.key());
            assertThat(secondDelivery.value()).isEqualTo(firstDelivery.value());

            // assert: 구매 효과는 한 번뿐이다
            assertThat(secondResult).isEqualTo(PurchaseActivityRecorder.Result.ALREADY_PROCESSED);
            List<ProductPurchaseActivity> activities = activityRepository.findByOrderIdOrderByProductId(42L);
            assertThat(activities).extracting(ProductPurchaseActivity::getProductId).containsExactly(7L, 8L);
            assertThat(activities).extracting(ProductPurchaseActivity::getQuantity).containsExactly(2, 1);
            assertThat(activityRepository.count()).isEqualTo(2);
            assertThat(KafkaLab.committedOffset(group, partition)).as("DB는 이미 반영됐지만 offset은 아직").isEqualTo(n);

            // act 5: 두 번째 처리가 끝났으니 다음 위치(n+1)를 commit한다 (listener의 ack가 하는 일)
            second.commitSync(Map.of(partition, new OffsetAndMetadata(n + 1)));
        } finally {
            second.close(Duration.ofSeconds(10));
        }

        // assert
        Long finalOffset = KafkaLab.committedOffset(group, partition);
        assertThat(finalOffset).isEqualTo(n + 1);
        System.out.printf("[offset 관찰] topic=%s partition=%d | 전달 2회(같은 offset=%d) | 활동 행 2 → 2 | committed offset %d → %d → %d%n",
            topic, partition.partition(), n, n, n, finalOffset);
    }
}
