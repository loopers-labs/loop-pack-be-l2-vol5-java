package com.loopers.kafka;

import com.loopers.interfaces.consumer.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaErrorHandling;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaListener;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 producer 기본 delivery timeout까지 기다리는 긴 실험이라 experimentTest에서만 실행한다. */
@Tag("experiment")
@SpringBootTest(classes = {KafkaFailureHandlingExperimentTest.DatabaseConfig.class,
    OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaErrorHandling.class,
    OrderConfirmedKafkaListener.class}, properties = {
        "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.concurrency=1",
        "commerce.order-events.consumer.auto-startup=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedDlqTimeoutExperimentTest extends KafkaPurchaseExperimentSupport {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "dlt-timeout-" + UUID.randomUUID();
    private static final String DLT = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final TopicPartition SOURCE = new TopicPartition(TOPIC, 0);
    private static final TopicPartition DEAD = new TopicPartition(DLT, 0);
    private static final byte[] INVALID = "not-json".getBytes(StandardCharsets.UTF_8);

    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired @Qualifier("orderConfirmedDeadLetterProducerFactory")
    private ProducerFactory<String, byte[]> deadLetterFactory;
    @Autowired @Qualifier(OrderConfirmedKafkaConfiguration.FACTORY)
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> orderFactory;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
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
    @Timeout(220)
    void delayedDeadLetterFailureKeepsSourceOffsetUntilManualRestart() throws Exception {
        var producerConfig = new ProducerConfig(deadLetterFactory.getConfigurationProperties());
        assertThat(producerConfig.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(120_000);
        assertThat(producerConfig.getString(ProducerConfig.ACKS_CONFIG)).isIn("all", "-1");
        var consumerConfig = new ConsumerConfig(orderFactory.getConsumerFactory().getConfigurationProperties());
        assertThat(consumerConfig.getInt(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo(300_000);
        long waitBudget = KafkaUtils.determineSendTimeout(
            deadLetterFactory.getConfigurationProperties(), 5_000L, 10_000L).toMillis();
        assertThat(waitBudget).isEqualTo(125_000L);

        var container = (ConcurrentMessageListenerContainer<?, ?>)
            registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
        assertThat(container).isNotNull();
        var received = new CountDownLatch(1);
        var startedAt = new AtomicLong();
        List<Long> deliveredOffsets = new CopyOnWriteArrayList<>();
        container.setRecordInterceptor((record, consumer) -> {
            deliveredOffsets.add(record.offset());
            startedAt.compareAndSet(0L, System.nanoTime());
            received.countDown();
            return record;
        });

        try (var admin = admin(); var producer = producer()) {
            // ISR이 항상 1인 테스트 topic에 최소 ISR 2를 요구해 DLT 쓰기만 재시도 가능 오류로 거절한다.
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
                new NewTopic(DLT, 1, (short) 1).configs(Map.of("min.insync.replicas", "2"))))
                .all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, GROUP, SOURCE);
            assertThat(producer.send(new ProducerRecord<>(TOPIC, "42", INVALID))
                .get(10, TimeUnit.SECONDS).offset()).isZero();
            assertThat(sendOrder(producer, TOPIC, 43L).offset()).isEqualTo(1L);

            long elapsedMillis;
            double retriesAtCheckpoint;
            double totalRetries;
            try (var deadLetterProducer = deadLetterFactory.createProducer()) {
                deadLetterProducer.partitionsFor(DLT);
                container.start();
                assertThat(received.await(25, TimeUnit.SECONDS)).isTrue();
                // 10초 상한이라는 가정과 다르게, 12초 뒤에도 실제 send 재시도가 진행되는지 관찰한다.
                TimeUnit.SECONDS.sleep(12);
                retriesAtCheckpoint = retries(deadLetterProducer);
                assertThat(retriesAtCheckpoint).isPositive();
                assertThat(container.isRunning()).isTrue();
                assertThat(committed(admin, GROUP, SOURCE)).isZero();
                assertThat(endOffset(admin, DEAD)).isZero();
                assertThat(deliveredOffsets).containsExactly(0L);
                assertOrders();

                long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
                while (container.isRunning() && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                assertThat(container.isRunning()).isFalse();
                elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt.get()).toMillis();
                assertThat(elapsedMillis).isBetween(110_000L, 160_000L);
                container.stop();
                totalRetries = retries(deadLetterProducer);
                assertThat(totalRetries).isGreaterThanOrEqualTo(retriesAtCheckpoint);
                assertThat(committed(admin, GROUP, SOURCE)).isZero();
                assertThat(endOffset(admin, DEAD)).isZero();
                assertOrders();
            }

            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, DLT),
                List.of(new AlterConfigOp(new ConfigEntry("min.insync.replicas", "1"), AlterConfigOp.OpType.SET))))
                .all().get(10, TimeUnit.SECONDS);
            // 앱 재시작 중 producer 재생성을 모사한다. container.start만으로의 복구를 주장하지 않는다.
            ((DefaultKafkaProducerFactory<?, ?>) deadLetterFactory).reset();
            container.start();
            await(() -> committed(admin, GROUP, SOURCE) == 2L && deliveredOffsets.size() == 3);
            assertThat(deliveredOffsets).containsExactly(0L, 0L, 1L);
            assertOrders(43L);
            assertThat(endOffset(admin, DEAD)).isEqualTo(1L);
            assertThat(endOffset(admin, SOURCE)).isEqualTo(2L);
            assertDeadLetter();
            report("DLT_TIMEOUT_EXPERIMENT", Map.ofEntries(
                Map.entry("deliveryTimeoutMs", 120_000), Map.entry("resultWaitBudgetMs", waitBudget),
                Map.entry("maxPollIntervalMs", 300_000), Map.entry("checkpointAfterSeconds", 12),
                Map.entry("retriesAtCheckpoint", retriesAtCheckpoint), Map.entry("totalRetries", totalRetries),
                Map.entry("stopAfterMillis", elapsedMillis), Map.entry("sourceOffsets", List.of(0, 0, 2)),
                Map.entry("deliveredOffsets", deliveredOffsets), Map.entry("deadLetterRecords", 1),
                Map.entry("confirmationRows", confirmations.count()), Map.entry("activityRows", activities.count()),
                Map.entry("producerRecreatedForRecovery", true)));
        } finally {
            container.stop();
        }
    }

    private double retries(Producer<String, byte[]> producer) {
        return producer.metrics().entrySet().stream()
            .filter(entry -> entry.getKey().group().equals("producer-metrics")
                && entry.getKey().name().equals("record-retry-total"))
            .mapToDouble(entry -> ((Number) entry.getValue().metricValue()).doubleValue()).sum();
    }

    private long endOffset(org.apache.kafka.clients.admin.AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
            .all().get(10, TimeUnit.SECONDS).get(partition).offset();
    }

    private void assertDeadLetter() {
        var settings = new HashMap<>(consumerSettings());
        try (var consumer = new KafkaConsumer<String, byte[]>(settings)) {
            consumer.assign(List.of(DEAD));
            consumer.seekToBeginning(List.of(DEAD));
            ConsumerRecord<String, byte[]> deadLetter = consumer.poll(Duration.ofSeconds(10)).iterator().next();
            assertThat(deadLetter.key()).isEqualTo("42");
            assertThat(deadLetter.value()).isEqualTo(INVALID);
            assertThat(ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value())
                .getLong()).isZero();
            assertThat(new String(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8)).isEqualTo(TOPIC);
            assertThat(new String(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(),
                StandardCharsets.UTF_8)).isEqualTo(GROUP);
        }
    }
}
