package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityService;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과제 6절 "DB commit 뒤 offset 미커밋 재전달" (📄 p.6~7 표). 같은 broker·topic·group·DB 를 유지하고 consumer 만 새로 만든다.
 * 애플리케이션의 실제 parser·업무 handler(PurchaseActivityService 의 DB TX)를 호출하되, 첫 consumer 의 완료 offset commit 만 보류한다.
 * 이 topic 은 앱의 Spring listener 가 구독하지 않으므로 두 low-level consumer 만 읽는다. 두 번째 consumer 는 seek·offset reset 을 하지 않는다.
 */
@SpringBootTest
class KafkaDeliveryTest {

    private static final String TOPIC = "order-events-KafkaDeliveryTest";
    private static final String GROUP = "purchase-activity-KafkaDeliveryTest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final long ORDER_ID = 46L;
    private static final String ORDER_46_JSON = """
        {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "order-confirmed:v1:46", "orderId": 46,
         "confirmedAt": "2026-09-07T01:00:00.123Z", "items": [{"productId": 7, "quantity": 2}]}
        """;

    private static final AdminClient ADMIN;

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
        ADMIN = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()));
    }

    @Autowired
    private OrderConfirmedEventParser parser;
    @Autowired
    private PurchaseActivityService purchaseActivityService;
    @Autowired
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeAdmin() {
        ADMIN.close();
    }

    @DisplayName("첫 consumer 가 DB commit 뒤 offset 을 commit 하지 않고 종료하면, 같은 group 의 새 consumer 가 같은 위치를 재전달받고 handler 는 no-op 이다. committed offset 은 n → n → n+1.")
    @Test
    void redeliversSameRecord_whenOffsetNotCommittedAfterDbCommit() throws Exception {
        // 준비 - target 발행, 실제 partition·offset n 확보
        RecordMetadata target = send(String.valueOf(ORDER_ID), ORDER_46_JSON);
        long n = target.offset();
        assertThat(target.partition()).isEqualTo(PARTITION.partition());
        List<String> deliveries = new ArrayList<>();
        List<Long> committedTrace = new ArrayList<>();

        // 시작 위치 - 첫 consumer 가 배정된 뒤 target 의 다음 읽을 위치 n 을 commit 하고 n 에서 읽는다 (준비 단계의 시작 offset 지정)
        KafkaConsumer<String, byte[]> first = consumer();
        first.subscribe(List.of(TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                first.commitSync(Map.of(PARTITION, new OffsetAndMetadata(n)));
                first.seek(PARTITION, n);
            }
        });

        // 첫 처리 - target poll → 실제 parser·handler → DB commit. 완료 offset n+1 은 요청하지 않는다
        ConsumerRecord<String, byte[]> firstDelivery = pollOne(first);
        assertThat(firstDelivery.offset()).isEqualTo(n);
        assertThat(first.assignment()).containsExactly(PARTITION);
        committedTrace.add(committedOffset());
        assertThat(committedTrace.get(0)).as("배정 뒤 commit 한 시작 위치").isEqualTo(n);
        purchaseActivityService.consume(parser.parse(firstDelivery.key(), firstDelivery.value()));
        deliveries.add(firstDelivery.topic() + "-" + firstDelivery.partition() + "@" + firstDelivery.offset());

        List<ProductPurchaseActivity> afterFirst = purchaseActivityRepository.findActivitiesByOrderId(ORDER_ID);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getQuantity()).isEqualTo(2);
        assertThat(purchaseActivityRepository.findProcessedByOrderId(ORDER_ID)).isPresent();
        assertThat(committedOffset()).as("DB commit 뒤에도 committed offset 은 n").isEqualTo(n);

        // 종료 - auto commit false, 대기 중 commit 없이 bounded close. close 가 commit 하지 않았는지 확인
        first.close(Duration.ofSeconds(5));
        committedTrace.add(committedOffset());
        assertThat(committedTrace.get(1)).as("종료 뒤에도 committed offset 은 n").isEqualTo(n);

        // 재시작 - 같은 group 의 새 consumer. seek·offset reset 없이 저장된 group offset 에서 읽는다
        KafkaConsumer<String, byte[]> second = consumer();
        second.subscribe(List.of(TOPIC));
        ConsumerRecord<String, byte[]> secondDelivery = pollOne(second);
        deliveries.add(secondDelivery.topic() + "-" + secondDelivery.partition() + "@" + secondDelivery.offset());

        // 두 번째 처리 - 같은 사실이라 no-op, 그 뒤 n+1 commit
        purchaseActivityService.consume(parser.parse(secondDelivery.key(), secondDelivery.value()));
        second.commitSync(Map.of(PARTITION, new OffsetAndMetadata(n + 1)));
        second.close(Duration.ofSeconds(5));
        committedTrace.add(committedOffset());

        // 관찰값 - 전달 2회, 동일 위치, 활동 행 수·수량 불변, committed n → n → n+1
        assertThat(deliveries).containsExactly(TOPIC + "-0@" + n, TOPIC + "-0@" + n);
        assertThat(secondDelivery.offset()).isEqualTo(firstDelivery.offset());
        assertThat(new String(secondDelivery.value(), StandardCharsets.UTF_8)).isEqualTo(new String(firstDelivery.value(), StandardCharsets.UTF_8));
        List<ProductPurchaseActivity> afterSecond = purchaseActivityRepository.findActivitiesByOrderId(ORDER_ID);
        assertThat(afterSecond).hasSize(1);
        assertThat(afterSecond.get(0).getId()).isEqualTo(afterFirst.get(0).getId());
        assertThat(afterSecond.get(0).getQuantity()).isEqualTo(2);
        assertThat(committedTrace).containsExactly(n, n, n + 1);
    }

    private static KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    /** 배정·수신을 유한하게 기다려 record 1건을 돌려준다. 배정 전에 잠만 자지 않고 poll 로 group 에 참여한다. */
    private static ConsumerRecord<String, byte[]> pollOne(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.currentTimeMillis() + DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(200));
            if (!records.isEmpty()) {
                assertThat(records.count()).as("target 1건만 있어야 한다").isEqualTo(1);
                return records.iterator().next();
            }
        }
        throw new AssertionError("기한 안에 record 를 받지 못했다. assignment=" + consumer.assignment());
    }

    private static RecordMetadata send(String key, String json) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(TOPIC, key, json.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
        }
    }

    /** group 이 commit 한 다음 읽을 위치. metadata 에서 구하며 값을 고정하지 않는다. */
    private static long committedOffset() throws Exception {
        OffsetAndMetadata committed = ADMIN.listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
        return committed == null ? -1 : committed.offset();
    }
}
