package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedRecordAdapter;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 업무용 구독을 켜지 않고 실제 Kafka·MySQL에 선택한 조건을 연결하는 테스트 지원 코드. */
@SpringBootTest(classes = KafkaFailureHandlingExperimentTest.DatabaseConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class KafkaPurchaseExperimentSupport {
    protected static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T01:00:00.123456Z");
    protected KafkaTestBroker broker;
    @Autowired protected OrderConfirmedRecordAdapter adapter;
    @Autowired protected ProductPurchaseActivityJpaRepository activities;
    @Autowired protected ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private DatabaseCleanUp cleanUp;

    @BeforeAll
    void startExperimentBroker() {
        broker = new KafkaTestBroker();
    }

    @AfterAll
    void stopExperimentBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    @AfterEach
    void cleanExperimentDatabase() {
        cleanUp.truncateAllTables();
    }

    protected AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()));
    }

    protected Map<String, Object> consumerSettings() {
        return Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    }

    protected ContainerProperties properties(String topic, String group, MessageListener<String, byte[]> listener) {
        var properties = new ContainerProperties(topic);
        properties.setGroupId(group);
        // 실험의 공통 조건이다. 업무용 ack mode를 채택하거나 기존 factory를 바꾸지 않는다.
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        properties.setSyncCommits(true);
        properties.setSyncCommitTimeout(Duration.ofSeconds(10));
        properties.setPollTimeout(100);
        properties.setShutdownTimeout(10_000);
        properties.setMessageListener(listener);
        return properties;
    }

    protected KafkaMessageListenerContainer<String, byte[]> container(
        String topic, String group, MessageListener<String, byte[]> listener, CountDownLatch stopped
    ) {
        var container = new KafkaMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<String, byte[]>(consumerSettings()), properties(topic, group, listener));
        container.setBeanName("purchase-experiment-" + group);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        container.setApplicationEventPublisher(event -> {
            if (event instanceof ConsumerStoppedEvent) {
                stopped.countDown();
            }
        });
        return container;
    }

    protected KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    protected RecordMetadata sendOrder(KafkaProducer<String, byte[]> producer, String topic, long orderId)
        throws Exception {
        return producer.send(new ProducerRecord<>(topic, Long.toString(orderId), payload(orderId)))
            .get(10, TimeUnit.SECONDS);
    }

    protected byte[] payload(long orderId) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"%s","items":[{"productId":7,"quantity":5},{"productId":8,"quantity":3}]}
            """.formatted(orderId, orderId, CONFIRMED_AT).getBytes(StandardCharsets.UTF_8);
    }

    protected void seedOffset(AdminClient admin, String group, TopicPartition partition) throws Exception {
        admin.alterConsumerGroupOffsets(group, Map.of(partition, new OffsetAndMetadata(0L)))
            .all().get(10, TimeUnit.SECONDS);
        assertThat(committed(admin, group, partition)).isZero();
    }

    protected long committed(AdminClient admin, String group, TopicPartition partition) throws Exception {
        var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(partition);
        return offset == null ? -1 : offset.offset();
    }

    protected void assertOrders(long... orderIds) {
        var expected = new ArrayList<org.assertj.core.groups.Tuple>();
        for (long orderId : orderIds) {
            expected.add(tuple(orderId, 7L, 5, CONFIRMED_AT));
            expected.add(tuple(orderId, 8L, 3, CONFIRMED_AT));
        }
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity,
            ProductPurchaseActivity::getConfirmedAt).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(confirmations.findAll()).extracting(ProcessedOrderConfirmation::getOrderId)
            .containsExactlyInAnyOrderElementsOf(Arrays.stream(orderIds).boxed().toList());
    }

    protected void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("expected Kafka/DB state was not observed");
    }

    protected void releaseAfterInspection(CountDownLatch gate) {
        try {
            if (!gate.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("inspection timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    protected void report(String name, Map<String, Object> result) throws Exception {
        System.out.println(name + " " + new ObjectMapper().writeValueAsString(result));
    }
}
