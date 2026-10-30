package com.loopers.interfaces.consumer;

import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// 과제 실험 ④ 사례 B의 "이미 소비된 1건". api OrderConfirmedGapCaseTest가 broker record와 같음을 확인한 fixture를
// 실제 order-events listener가 소비하게 한다. committed offset은 (group, topic, partition)마다 따로라 topic만 고유하게 하고
// 운영 group(purchase-activity)은 그대로 둔다.
@SpringBootTest
class OrderConfirmedGapCaseConsumedTest {

    private static final String TOPIC = "order-events-gap-case-b-" + UUID.randomUUID();
    private static final String CASE_B_FIXTURE = "contracts/order-confirmed-gap-case-b.json";
    private static final String CASE_B_KEY = "1";
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.parse("2026-10-29T01:00:00");

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private OrderEventsListenerFixture fixture;
    private PurchaseActivityTables tables;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
    }

    @BeforeEach
    void setUp() {
        fixture = new OrderEventsListenerFixture(bootstrapServers, TOPIC, kafkaListenerEndpointRegistry);
        tables = new PurchaseActivityTables(jdbcTemplate);
        fixture.awaitAssigned();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사례 B record(주문 1, 상품 1×2·2×1)를 실제 order-events listener가 소비하면 완료 기록 1행·구매 활동 2행이 남고 group committed offset이 n+1이 된다")
    @Test
    void caseBRecordIsConsumedByListener() throws Exception {
        Long committedBefore = fixture.committed();

        long n = fixture.send(CASE_B_KEY, caseBFixture());

        fixture.awaitCommitted(n + 1);
        assertThat(committedBefore).isNull();
        assertThat(tables.confirmations()).isEqualTo(1);
        List<Map<String, Object>> activities = tables.activitiesOf(1);
        assertThat(activities).hasSize(2);
        assertThat(activities.get(0)).containsEntry("product_id", 1L).containsEntry("quantity", 2)
            .containsEntry("confirmed_at", CONFIRMED_AT);
        assertThat(activities.get(1)).containsEntry("product_id", 2L).containsEntry("quantity", 1)
            .containsEntry("confirmed_at", CONFIRMED_AT);
        assertThat(tables.anomalies()).isZero();

        System.out.println("[실험④ 사례 B 소비] topic=" + TOPIC + ", partition=0, offset " + n + ", committed 보내기 전="
            + (committedBefore == null ? "없음" : committedBefore) + " → 처리 후 " + fixture.committed() + ", 완료 기록 "
            + tables.confirmations() + "행, 구매 활동 " + activities.size() + "행(" + activities + ")");
    }

    // 파일 끝 줄바꿈은 record 값이 아니므로 떼고 보낸다.
    private static String caseBFixture() throws IOException {
        try (InputStream input = new ClassPathResource(CASE_B_FIXTURE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
