package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=" + OrderConfirmedListenerStopTest.TOPIC,
    "kafka.groups.purchase-activity=" + OrderConfirmedListenerStopTest.GROUP
})
class OrderConfirmedListenerStopTest {

    static final String TOPIC = "order-events-stop";
    static final String GROUP = "purchase-activity-stop";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderConfirmedListenerStopTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                                   ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                                   DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void send(byte[] value) throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, null, "42", value);
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private OffsetAndMetadata committed() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, 0));
        }
    }

    @DisplayName("ACT-010, KAFKA-006: 모르는 schemaVersion 이 오면 보류하지 않고 멈추고, committed offset 이 그 record 를 지나지 않아 뒤 record 도 반영되지 않는다")
    @Test
    void stopsOnUnknownSchemaVersion() throws Exception {
        ObjectNode nextVersion = (ObjectNode) objectMapper.readTree(OrderConfirmedContract.example());
        nextVersion.put("schemaVersion", 2);

        send(objectMapper.writeValueAsBytes(nextVersion));
        send(OrderConfirmedContract.example().getBytes(StandardCharsets.UTF_8));

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(100);
        }
        OffsetAndMetadata committed = committed();
        assertThat(committed == null ? 0L : committed.offset()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_confirmation_receipt", Integer.class))
            .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_event_hold", Integer.class)).isZero();
    }
}
