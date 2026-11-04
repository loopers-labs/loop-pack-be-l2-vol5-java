package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaErrorHandling;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaListener;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 발행 사례 재현 2단계. commerce-api의 W6DeliveryCasesPublishTest가 로컬 브로커 3대의
 * order-events-w6-cases에 남긴 record를 실제 주문 listener(group purchase-activity·concurrency 3·업무용 오류 처리기)로 소비한다.
 * 사례 B만 구매 활동이 생기고, send 직전에 멈춘 사례 A는 record가 없으므로 구매 활동도 없다.
 * 성공하면 사례 topic을 지운다. 실행: {@code ./gradlew :apps:commerce-streamer:experimentTest --tests '*W6DeliveryCasesConsumeTest'}
 */
@Tag("experiment")
@SpringBootTest(classes = {KafkaFailureHandlingExperimentTest.DatabaseConfig.class,
    OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaErrorHandling.class,
    OrderConfirmedKafkaListener.class}, properties = {
        "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.concurrency=3",
        "commerce.order-events.consumer.auto-startup=false",
        "commerce.order-events.topic=" + W6DeliveryCasesConsumeTest.TOPIC})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class W6DeliveryCasesConsumeTest {
    static final String TOPIC = "order-events-w6-cases";
    private static final String LOCAL_BROKERS = "localhost:19092,localhost:29092,localhost:39092";
    private static final String GROUP = "purchase-activity";
    private static final List<TopicPartition> PARTITIONS = List.of(
        new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1), new TopicPartition(TOPIC, 2));

    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private DatabaseCleanUp cleanUp;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", () -> LOCAL_BROKERS);
    }

    @AfterAll
    void stop() {
        registry.stop();
        cleanUp.truncateAllTables();
    }

    @Test
    void storesPurchaseActivityOnlyForThePublishedCase() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, LOCAL_BROKERS))) {
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS))
                .as("먼저 commerce-api의 W6DeliveryCasesPublishTest를 실행한다").contains(TOPIC);
            admin.alterConsumerGroupOffsets(GROUP, PARTITIONS.stream()
                .collect(Collectors.toMap(partition -> partition, partition -> new OffsetAndMetadata(0L))))
                .all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, Long> ends = endOffsets();
            List<String> publishedKeys = publishedKeys();

            var container = registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
            container.start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (!committed(admin).equals(ends)) {
                    assertThat(System.nanoTime()).as("모든 partition의 committed offset이 끝까지 진행").isLessThan(deadline);
                    Thread.sleep(200);
                }
            } finally {
                container.stop();
            }

            assertThat(publishedKeys).hasSize(1);
            Long publishedOrderId = Long.valueOf(publishedKeys.getFirst());
            assertThat(confirmations.findAll()).extracting(ProcessedOrderConfirmation::getOrderId)
                .containsExactly(publishedOrderId);
            assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId).containsOnly(publishedOrderId);

            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("role", "B_CONSUMED");
            facts.put("group", GROUP);
            facts.put("committedOffsets", ends.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().partition(), Map.Entry::getValue)));
            facts.put("processedEventIds", confirmations.findAll().stream().map(ProcessedOrderConfirmation::getEventId).toList());
            facts.put("activities", activities.findAll().stream().map(activity -> Map.of(
                "orderId", activity.getOrderId(), "productId", activity.getProductId(),
                "quantity", activity.getQuantity(), "confirmedAt", activity.getConfirmedAt().toString())).toList());
            facts.put("otherOrdersWithActivity", 0);
            System.out.println("W6_DELIVERY_CASE " + new ObjectMapper().writeValueAsString(facts));

            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        }
    }

    private Map<TopicPartition, Long> committed(AdminClient admin) throws Exception {
        Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
        var stored = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        for (TopicPartition partition : PARTITIONS) {
            OffsetAndMetadata offset = stored.get(partition);
            offsets.put(partition, offset == null ? -1L : offset.offset());
        }
        return offsets;
    }

    private Map<TopicPartition, Long> endOffsets() {
        try (var consumer = reader()) {
            return new LinkedHashMap<>(consumer.endOffsets(PARTITIONS));
        }
    }

    private List<String> publishedKeys() {
        try (var consumer = reader()) {
            consumer.assign(PARTITIONS);
            consumer.seekToBeginning(PARTITIONS);
            long total = consumer.endOffsets(PARTITIONS).values().stream().mapToLong(Long::longValue).sum();
            List<String> keys = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (keys.size() < total && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
            }
            return keys;
        }
    }

    private KafkaConsumer<String, byte[]> reader() {
        return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, LOCAL_BROKERS,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }
}
