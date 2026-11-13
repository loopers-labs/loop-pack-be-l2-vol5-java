package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponIssuePublisher.Outcome;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CouponIssueKafkaPublisherTest {

    private final CouponIssuePublisher publisher;
    private final ObjectMapper objectMapper;

    @Autowired
    CouponIssueKafkaPublisherTest(CouponIssuePublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @DisplayName("PUB-001: 예약 하나가 메시지 하나이고, key 는 캠페인 ID, 내용은 캠페인과 사용자와 번호와 예약 시각이다")
    @Test
    void publishesOneRecordPerReservation() throws Exception {
        CouponIssue issue = new CouponIssue(7L, 42L, 3, Instant.parse("2026-11-08T00:00:00.123456Z"));
        AtomicReference<Outcome> outcome = new AtomicReference<>();

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(KafkaTestContainersConfig.COUPON_ISSUES,
            () -> outcome.set(publisher.publish(issue)));

        assertThat(outcome.get()).isEqualTo(new Outcome.Published());
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isNull();
            JsonNode body = objectMapper.readTree(record.value());
            assertThat(body.get("eventType").asText()).isEqualTo("CouponIssue");
            assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(body.get("campaignId").asLong()).isEqualTo(7L);
            assertThat(body.get("userId").asLong()).isEqualTo(42L);
            assertThat(body.get("issueNumber").asInt()).isEqualTo(3);
            assertThat(body.get("reservedAt").asText()).isEqualTo("2026-11-08T00:00:00.123456Z");
        });
    }
}
