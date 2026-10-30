package com.loopers.interfaces.consumer;

import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
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
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private boolean checkInjected;

    @Autowired
    ListenerFailureExperimentTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                                  MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate,
                                  DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.meterRegistry = meterRegistry;
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

    private double retries() {
        return meterRegistry.find("purchase.activity.retry").counters().stream()
            .mapToDouble(counter -> counter.count())
            .sum();
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

    @DisplayName("EXP-006: 둘째 품목이 DB 에서 거절되면 처리 기록과 첫 품목까지 rollback 되고, 컨테이너는 멈추지 않은 채 그 record 를 다시 시도하며 committed offset 이 그 record 에 머문다. 원인을 걷어 내면 사람의 재시작 없이 반영하고 넘어간다")
    @Test
    void retriesDbFailureAndRecoversWithoutRestart() throws Exception {
        double retriesBefore = retries();
        Long committedBefore = committed();
        jdbcTemplate.execute(
            "alter table purchase_activity add constraint ck_exp_reject_product_8 check (product_id <> 8)");
        checkInjected = true;

        RecordMetadata target = send();

        eventually(() -> retries() - retriesBefore >= 2, Duration.ofSeconds(30));
        Long committedWhileFailing = committed();
        int receiptsWhileFailing = count("order_confirmation_receipt");
        int activitiesWhileFailing = count("purchase_activity");
        boolean runningWhileFailing =
            registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning();

        jdbcTemplate.execute("alter table purchase_activity drop check ck_exp_reject_product_8");
        checkInjected = false;
        long fixedAt = System.nanoTime();

        eventually(() -> count("purchase_activity") == 2, Duration.ofSeconds(60));
        Duration recoveredIn = Duration.ofNanos(System.nanoTime() - fixedAt);
        eventually(() -> {
            try {
                Long offset = committed();
                return offset != null && offset > target.offset();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, Duration.ofSeconds(30));

        System.out.printf("EXP-006 target=%d, 보내기 전 committed=%s, 실패 중 committed=%s 처리 기록=%d 활동=%d 컨테이너 실행=%s "
                + "재시도=%s, 원인 제거 뒤 반영까지=%s, committed=%s 처리 기록=%d 활동=%d%n",
            target.offset(), committedBefore, committedWhileFailing, receiptsWhileFailing, activitiesWhileFailing,
            runningWhileFailing, retries() - retriesBefore, recoveredIn, committed(),
            count("order_confirmation_receipt"), count("purchase_activity"));
        assertThat(committedWhileFailing == null ? 0L : committedWhileFailing).isLessThanOrEqualTo(target.offset());
        assertThat(receiptsWhileFailing).isZero();
        assertThat(activitiesWhileFailing).isZero();
        assertThat(runningWhileFailing).isTrue();
        assertThat(count("order_confirmation_receipt")).isEqualTo(1);
    }
}
