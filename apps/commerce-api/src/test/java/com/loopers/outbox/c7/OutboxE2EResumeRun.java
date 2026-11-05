package com.loopers.outbox.c7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C-6 3막 E-2 — relay 재시작(새 JVM). E-1이 SENDING으로 남긴 같은 Outbox 행을, lease(맡은 시각 + 1분)가 실제로 지난 뒤 다시 보낸다.
 * 실제 broker의 기록과 consumer 로그·DB를 관찰한다 — send 호출 횟수만 세지 않는다 (과제 7절).
 * <p>
 * 기대 (과제 표): 주문·PG 거래 각 1 · Outbox 같은 eventId 1행·최종 PUBLISHED · Kafka 같은 eventId의 별도 record 2개 이상 ·
 * consumer 수신 2회 이상 · 완료 기록 1 · 구매 활동 상품별 2행(7×2, 8×1) · 예약 추가 소비·반환 없음, 포인트 추가 차감 없음.
 */
@Tag("isolated")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false"})
class OutboxE2EResumeRun {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> System.getenv("C7_JDBC_URL"));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("C7_BOOTSTRAP"));
        registry.add("pg.local.base-url", () -> System.getenv("C7_PG_URL"));
    }

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void restartedRelayResendsSameEventAndPurchaseEffectStays() throws Exception {
        JsonNode e1 = objectMapper.readTree(Files.readString(Path.of(System.getenv("C7_E2E"))));
        long orderId = e1.get("orderId").asLong();
        long userId = e1.get("userId").asLong();
        String eventId = e1.get("eventId").asText();

        // 재시작 직후: 행은 아직 SENDING(lease 안) — 보내는 중으로 보고 고르지 않는다
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM order_outbox WHERE event_id = ?", String.class, eventId)).isEqualTo("SENDING");

        // act: lease가 지날 때까지 회차를 돌린다 (실제 시각, 최대 90초)
        AtomicReference<RelayResult> resent = new AtomicReference<>();
        long started = System.currentTimeMillis();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            RelayResult result = relay.relayOnce();
            if (result.published() > 0) {
                resent.set(result);
                return true;
            }
            return false;
        });
        long waitedMillis = System.currentTimeMillis() - started;

        // 관찰: broker 기록 · consumer 로그 · DB
        List<ConsumerRecord<String, String>> records = KafkaTopicReader.read(System.getenv("C7_BOOTSTRAP"), "order-events",
            r -> r.value().contains("\"eventId\":\"" + eventId + "\""), 3, Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(30)).until(() -> consumerReceipts(eventId).size() >= 2);
        Thread.sleep(2_000);
        List<String> receipts = consumerReceipts(eventId);
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count AS attempts, published_partition AS publishedPartition, published_offset AS publishedOffset FROM order_outbox WHERE event_id = ?", eventId);
        Map<String, Object> business = IsolatedFacts.business(jdbcTemplate, orderId, userId, List.of(7L, 8L));
        Map<String, Object> pg = IsolatedFacts.pgStats(objectMapper);
        List<Map<String, Object>> activity = IsolatedFacts.activity(jdbcTemplate, orderId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resent", resent.get());
        result.put("waitedMillisForLease", waitedMillis);
        result.put("records", records.stream().map(r -> Map.of("partition", r.partition(), "offset", r.offset(), "key", r.key())).toList());
        result.put("consumerReceipts", receipts);
        result.put("outbox", outbox);
        result.put("outboxRowsForOrder", count("SELECT COUNT(*) FROM order_outbox WHERE order_id = ?", orderId));
        result.put("completed", count("SELECT COUNT(*) FROM consumed_order_confirmation WHERE order_id = ? AND event_id = ?", orderId, eventId));
        result.put("activity", activity);
        result.put("businessAfterConfirm", objectMapper.convertValue(e1.get("businessAfterConfirm"), Map.class));
        result.put("businessNow", business);
        result.put("pgAfterConfirm", objectMapper.convertValue(e1.get("pgAfterConfirm"), Map.class));
        result.put("pgNow", pg);
        Files.writeString(Path.of(System.getenv("C7_E2E_RESULT")), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("[C-6 E-2] " + objectMapper.writeValueAsString(result));

        // assert (과제 표)
        assertThat(resent.get()).isEqualTo(new RelayResult(1, 1, null));
        assertThat(records).hasSizeGreaterThanOrEqualTo(2)
            .allSatisfy(r -> assertThat(r.value()).isEqualTo(e1.get("payload").asText()));
        assertThat(records.stream().map(r -> r.partition() + "@" + r.offset()).distinct()).hasSize(records.size()); // 별도 offset
        assertThat(receipts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(receipts.get(0)).contains("구매 활동 저장");
        assertThat(receipts.subList(1, receipts.size())).allMatch(line -> line.contains("no-op"));
        assertThat(outbox.get("status")).isEqualTo("PUBLISHED");
        assertThat(result.get("outboxRowsForOrder")).isEqualTo(1L);
        assertThat(result.get("completed")).isEqualTo(1L);
        assertThat(activity).containsExactly(
            Map.of("productId", 7L, "quantity", 2),
            Map.of("productId", 8L, "quantity", 1));
        // 막 사이 비교는 JSON으로 맞춰서 한다 — DB에서 읽은 Long과 파일에서 읽은 Integer가 같은 값이어도 equals가 false다 (C-7에서 겪음)
        assertThat(json(business)).isEqualTo(e1.get("businessAfterConfirm"));
        assertThat(json(pg)).isEqualTo(e1.get("pgAfterConfirm"));
        assertThat(count("SELECT COUNT(*) FROM orders WHERE id = ?", orderId)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1L);
    }

    /** streamer 로그에서 이 eventId를 처리한 줄 — 수신 1번마다 "구매 활동 저장" 또는 "no-op" 한 줄 */
    private List<String> consumerReceipts(String eventId) throws Exception {
        return Files.readAllLines(Path.of(System.getenv("C7_STREAMER_LOG"))).stream()
            .filter(line -> line.contains("eventId=" + eventId))
            .filter(line -> line.contains("구매 활동 저장") || line.contains("no-op"))
            .toList();
    }

    private JsonNode json(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
