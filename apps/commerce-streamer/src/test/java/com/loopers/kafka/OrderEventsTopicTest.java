package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {KafkaTestContainersConfig.class, KafkaConfig.class, JacksonAutoConfiguration.class})
class OrderEventsTopicTest {

    private static final String ORDER_EVENTS = KafkaTestContainersConfig.ORDER_EVENTS;

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final KafkaProperties kafkaProperties;

    @Autowired
    OrderEventsTopicTest(KafkaTemplate<Object, Object> kafkaTemplate, KafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    @DisplayName("EVT-001: order-events 는 partition 3 개로 미리 만들어져 있다.")
    @Test
    void orderEventsExistsWithThreePartitions() throws Exception {
        try (Admin admin = Admin.create(kafkaProperties.buildAdminProperties(null))) {
            TopicDescription topic = admin.describeTopics(List.of(ORDER_EVENTS))
                .allTopicNames()
                .get(10, TimeUnit.SECONDS)
                .get(ORDER_EVENTS);

            assertThat(topic.partitions()).hasSize(3);
        }
    }

    @DisplayName("EVT-001: 없는 topic 으로 보내면 1 초 만에 실패하고, broker 가 그 topic 을 만들지 않는다.")
    @Test
    void sendingToMissingTopicFailsWithoutCreatingIt() throws Exception {
        String missingTopic = "missing-" + UUID.randomUUID();
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> kafkaTemplate.send(missingTopic, "1", new byte[] {1}).get(10, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(TimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofSeconds(3));
        try (Admin admin = Admin.create(kafkaProperties.buildAdminProperties(null))) {
            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);

            assertThat(topics).doesNotContain(missingTopic);
        }
    }
}
