package com.loopers.couponissue.adapter.in.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.config.CouponIssueKafkaConfig;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.loopers.couponissue.support.CouponIssueFixtures.campaignWithTwoCoupons;
import static com.loopers.couponissue.support.CouponIssueFixtures.issuedCount;
import static com.loopers.couponissue.support.CouponIssueFixtures.userCoupons;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 listener (D-8): coupon-issue-requests의 메시지로 쿠폰팩을 발급한다. 계약에 맞지 않는 메시지는 재시도 뒤 DLT로 간다.
 */
class CouponIssueListenerIntegrationTest extends StreamerIntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(40);

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Value("${kafka.topics.coupon-issue}")
    private String couponIssueTopic;

    @BeforeEach
    void awaitCouponListenerAssignment() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(CouponIssueKafkaConfig.LISTENER_ID);
        await().atMost(Duration.ofSeconds(30)).until(() -> container.getAssignedPartitions() != null
            && container.getAssignedPartitions().size() == 3);
        KafkaTestSupport.awaitProducerMetadata(kafkaTemplate, couponIssueTopic);
    }

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send(new ProducerRecord<>(couponIssueTopic, key, value)).get(10, TimeUnit.SECONDS);
    }

    @DisplayName("메시지를 받으면 쿠폰팩을 발급하고, 같은 메시지가 다시 와도 한 번만 발급한다.")
    @Test
    void issuesFromMessage() throws Exception {
        campaignWithTwoCoupons(jdbcTemplate, 7L, 3);

        send("42", "{\"campaignId\":7,\"userId\":42}");
        send("42", "{\"campaignId\":7,\"userId\":42}");
        send("43", "{\"campaignId\":7,\"userId\":43}");

        await().atMost(WAIT).untilAsserted(() -> assertThat(issuedCount(jdbcTemplate, 7L)).isEqualTo(2));
        assertThat(userCoupons(jdbcTemplate, 7L, 42L)).isEqualTo(2);
        assertThat(userCoupons(jdbcTemplate, 7L)).isEqualTo(4);
    }

    @DisplayName("계약에 맞지 않는 메시지(userId 문자열)는 발급하지 않고 DLT에 원문이 남는다.")
    @Test
    void invalidMessageGoesToDeadLetter() throws Exception {
        campaignWithTwoCoupons(jdbcTemplate, 8L, 3);
        String invalid = "{\"campaignId\":8,\"userId\":\"x-" + System.nanoTime() + "\"}";

        send("x", invalid);

        List<ConsumerRecord<String, String>> dead = KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(),
            couponIssueTopic + CouponIssueKafkaConfig.DLT_SUFFIX, record -> invalid.equals(record.value()), 1, WAIT);
        assertThat(dead).hasSize(1);
        assertThat(userCoupons(jdbcTemplate, 8L)).isZero();
    }
}
