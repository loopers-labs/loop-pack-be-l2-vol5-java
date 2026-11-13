package com.loopers.interfaces.consumer;

import com.loopers.support.CouponTables;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 실제 Kafka · MySQL 로 접수 topic 이 DB 접수 기록이 되는지 확인한다 (8주차 D-132) */
@SpringBootTest
class CouponIssueRequestConsumerTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private CouponTables couponTables;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${coupon-issue.topic}")
    private String topic;

    @AfterEach
    void tearDown() {
        couponTables.truncate();
    }

    private static String bootstrap() {
        return System.getProperty("spring.kafka.bootstrap-servers");
    }

    private void send(String key, String body) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, body.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
        }
    }

    private static String message(long campaignId, long userId, long sequence) {
        return "{\"schemaVersion\":1,\"campaignId\":" + campaignId + ",\"userId\":" + userId
            + ",\"sequence\":" + sequence + ",\"requestedAt\":\"2026-11-13T10:00+09:00\"}";
    }

    private long countOf(long campaignId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issue_request WHERE campaign_id = ?", Long.class, campaignId);
    }

    private void awaitCount(long campaignId, long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (countOf(campaignId) < expected) {
            assertThat(Instant.now()).as("접수 기록 적재 대기").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    private List<ConsumerRecord<String, byte[]>> dltRecordsWithKey(String key) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic + ".DLT").stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue()) && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
            return found;
        }
    }

    @DisplayName("접수 topic 의 record 는 DB 접수 기록이 되고, 재전송된 같은 접수는 한 행이다. (D-132, D-134)")
    @Test
    void loadsRecords_andAbsorbsResend() throws Exception {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(1);

        // act
        send(campaignId + ":7", message(campaignId, 7, 1));
        send(campaignId + ":7", message(campaignId, 7, 1));
        send(campaignId + ":8", message(campaignId, 8, 2));
        awaitCount(campaignId, 2);

        // assert
        assertAll(
            () -> assertThat(countOf(campaignId)).isEqualTo(2L),
            () -> assertThat(jdbc.queryForObject(
                "SELECT status FROM coupon_issue_request WHERE campaign_id = ? AND user_id = 8", String.class, campaignId))
                .isEqualTo("RESERVE")
        );
    }

    @DisplayName("형식이 계약과 다른 record 는 DLT 로 옮기고, 뒤의 record 는 계속 적재한다.")
    @Test
    void movesMalformedToDlt_andContinues() throws Exception {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        String badKey = campaignId + ":9";

        // act
        send(badKey, "not-json");
        send(campaignId + ":7", message(campaignId, 7, 1));
        awaitCount(campaignId, 1);

        // assert
        assertAll(
            () -> assertThat(countOf(campaignId)).isEqualTo(1L),
            () -> assertThat(dltRecordsWithKey(badKey)).hasSize(1)
        );
    }
}
