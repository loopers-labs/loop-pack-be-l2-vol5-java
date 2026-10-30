package com.loopers.interfaces.consumer;

import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=" + ListenerFailureExperimentTest.TOPIC,
    "kafka.groups.purchase-activity=" + ListenerFailureExperimentTest.GROUP
})
class ListenerFailureExperimentTest {

    static final String TOPIC = "exp-listener-failure";
    static final String GROUP = "exp-listener-failure";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static volatile CountDownLatch stopped = new CountDownLatch(1);

    @TestConfiguration
    static class StopWatcher {

        @EventListener
        void onStopped(ContainerStoppedEvent event) {
            if (PurchaseActivityListenerConfig.LISTENER_ID.equals(
                    event.getContainer(MessageListenerContainer.class).getListenerId())) {
                stopped.countDown();
            }
        }
    }

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private boolean checkInjected;

    @Autowired
    ListenerFailureExperimentTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                                  JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (checkInjected) {
            jdbcTemplate.execute("alter table purchase_activity drop check ck_exp_reject_product_8");
        }
        databaseCleanUp.truncateAllTables();
    }

    private RecordMetadata send() throws Exception {
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(TOPIC, null, "42",
            OrderConfirmedContract.example().getBytes(StandardCharsets.UTF_8));
        return kafkaTemplate.send(producerRecord).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private Long committed() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS)
                .get(PARTITION);
            return offset == null ? null : offset.offset();
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void eventually(BooleanSupplier condition, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(within + " 안에 조건이 채워지지 않았다");
            }
            Thread.sleep(100);
        }
    }

    @DisplayName("EXP-006: 둘째 품목이 DB 에서 거절되면 처리 기록과 첫 품목까지 rollback 되고 컨테이너가 멈추며 committed offset 이 그 record 에 머문다. 원인을 걷고 재시작하면 같은 record 를 다시 받아 반영하고 넘어간다")
    @Test
    void stopsOnDbFailureAndResumesAfterFix() throws Exception {
        stopped = new CountDownLatch(1);
        Long committedBefore = committed();
        jdbcTemplate.execute(
            "alter table purchase_activity add constraint ck_exp_reject_product_8 check (product_id <> 8)");
        checkInjected = true;

        RecordMetadata target = send();

        assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
        Long committedAtStop = committed();
        int receiptsAtStop = count("order_confirmation_receipt");
        int activitiesAtStop = count("purchase_activity");

        jdbcTemplate.execute("alter table purchase_activity drop check ck_exp_reject_product_8");
        checkInjected = false;
        registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).start();

        eventually(() -> count("purchase_activity") == 2, Duration.ofSeconds(30));
        eventually(() -> {
            try {
                Long offset = committed();
                return offset != null && offset > target.offset();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, Duration.ofSeconds(30));

        System.out.printf("EXP-006 target=%d, 보내기 전 committed=%s, 멈춘 뒤 committed=%s, 멈춘 뒤 처리 기록=%d 활동=%d, "
                + "재시작 뒤 committed=%s 처리 기록=%d 활동=%d%n",
            target.offset(), committedBefore, committedAtStop, receiptsAtStop, activitiesAtStop,
            committed(), count("order_confirmation_receipt"), count("purchase_activity"));
        assertThat(committedAtStop == null ? 0L : committedAtStop).isLessThanOrEqualTo(target.offset());
        assertThat(receiptsAtStop).isZero();
        assertThat(activitiesAtStop).isZero();
        assertThat(count("order_confirmation_receipt")).isEqualTo(1);
        assertThat(registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning()).isTrue();
    }
}
