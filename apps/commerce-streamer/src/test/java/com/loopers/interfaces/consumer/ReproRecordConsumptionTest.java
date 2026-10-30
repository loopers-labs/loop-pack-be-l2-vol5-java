package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.support.kafka.KafkaLab;
import com.loopers.testcontainers.KafkaBrokerFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * commerce-api의 누락 재현 테스트({@code OrderConfirmedOmissionReproTest})가 실제 broker에서 관찰한 <b>사례 B의 record</b>를
 * 실제 listener·파서·handler·DB로 소비해 구매 활동이 되는지 확인한다. 사례 A(commit 뒤 send 직전 중단)는 broker에 record가 없으므로
 * 보낼 것이 없고, 그 주문(orderId=1)의 구매 활동은 만들어지지 않는다 — 확정 주문은 남았지만 구매 활동이 누락되는 W7 개선 대상이다.
 *
 * <p>record 값은 api 재현 실행이 broker에서 읽은 바이트 그대로다({@code repro/case-b-record-value.json}, key는 "2").
 * 두 앱은 별도 모듈이라 한 JVM에서 이어 실행하지 않고, 이 실제 record가 둘을 잇는다. 값은 W6 재현 실행의 관찰값이며
 * W7의 새 격리 실행은 새 주문에서 새 사건 ID를 얻는다.
 */
@SpringBootTest
class ReproRecordConsumptionTest {

    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-repro-consume", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-repro");

    private static final long OMITTED_ORDER_ID = 1L;
    private static final long PUBLISHED_ORDER_ID = 2L;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
        registry.add("order-event.topic-name", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
        registry.add("order-event.listener-auto-startup", () -> "true");
    }

    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;

    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사례 B의 실제 record는 구매 활동 1건으로 저장되고 ack되며, broker record가 없던 사례 A의 주문은 구매 활동이 없다.")
    @Test
    void publishedRecordBecomesActivity_whileOmittedOrderHasNone() throws Exception {
        // arrange: api 재현 실행이 broker에서 읽은 사례 B의 record 값
        String value = new String(new ClassPathResource("repro/case-b-record-value.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // act: 사례 B의 record만 보낸다 (사례 A는 broker에 record가 없어서 보낼 것이 없다)
        RecordMetadata sent;
        try (KafkaProducer<String, String> producer = KafkaLab.stringProducer()) {
            sent = producer.send(new ProducerRecord<>(TOPIC, String.valueOf(PUBLISHED_ORDER_ID), value)).get(30, TimeUnit.SECONDS);
        }

        // assert: 실제 listener가 소비해 저장하고 ack한다
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(processedRepository.findByEventId("order-confirmed:v1:" + PUBLISHED_ORDER_ID)).isPresent());
        List<ProductPurchaseActivity> activities = activityRepository.findByOrderIdOrderByProductId(PUBLISHED_ORDER_ID);
        assertThat(activities).hasSize(1);
        assertThat(activities.get(0).getProductId()).isEqualTo(1L);
        assertThat(activities.get(0).getQuantity()).isEqualTo(2);
        assertThat(activities.get(0).getConfirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(KafkaLab.committedOffset(GROUP, new TopicPartition(TOPIC, sent.partition()))).isEqualTo(sent.offset() + 1));

        // assert: 사례 A의 주문은 확정됐지만(api DB) 구매 활동은 없다
        assertThat(processedRepository.findByOrderId(OMITTED_ORDER_ID)).isEmpty();
        assertThat(activityRepository.findByOrderIdOrderByProductId(OMITTED_ORDER_ID)).isEmpty();
        assertThat(activityRepository.count()).isEqualTo(1);
        System.out.printf("[누락 재현·소비] 사례 B(orderId=%d): 활동 1행(productId=1, quantity=2, confirmedAt=2026-09-07T01:00:00Z), "
            + "committed offset=%d | 사례 A(orderId=%d): 활동 0행%n", PUBLISHED_ORDER_ID, sent.offset() + 1, OMITTED_ORDER_ID);
    }
}
