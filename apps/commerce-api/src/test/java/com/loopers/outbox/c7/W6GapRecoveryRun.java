package com.loopers.outbox.c7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.c7.W6GapRecovery.Result;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C-7·C-8 2막 — 1막(W6 revision)이 남긴 같은 DB·broker·PG 위에 W7 코드를 띄워 W6 누락을 같은 사건으로 복구한다.
 * docs/week7/c7/run.sh가 {@code ./gradlew :apps:commerce-api:c7Recovery}로 실행한다. 보통 test 작업에서는 제외된다(@Tag c7).
 * <p>
 * 확인 (과제 원문 5절):
 * <ul>
 *   <li>복구 도구(테스트 전용)가 저장된 확정 사실의 eventId·payload 그대로 Outbox 행을 없을 때만 추가한다</li>
 *   <li>relay → 기존 consumer(W6 streamer, 1막부터 계속 실행 중): A는 빠졌던 구매 활동이 생기고, B는 재발행이 와도 활동 행·수량이 늘지 않는다</li>
 *   <li>같은 대상의 두 번째 복구는 Outbox·구매 효과를 늘리지 않는다</li>
 *   <li>C-8: 준비와 복구의 PG 호출 수를 나눠 읽는다. 복구 중 PG 호출 0 · 주문 재확정 없음 · 예약 추가 소비 없음</li>
 * </ul>
 * 정리를 하지 않는다 — 자원 정리는 모든 확인 뒤 스크립트가 한다.
 */
@Tag("c7")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false"})
class W6GapRecoveryRun {

    private static final String PG_SIM = System.getenv("C7_PG_URL");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> System.getenv("C7_JDBC_URL"));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("C7_BOOTSTRAP"));
        registry.add("pg.local.base-url", () -> PG_SIM);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private OrderOutboxPort orderOutboxPort;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void recoverW6GapAsTheSameEvents() throws Exception {
        JsonNode facts = objectMapper.readTree(Files.readString(Path.of(System.getenv("C7_FACTS"))));
        long orderA = facts.get("A").get("orderId").asLong();
        long orderB = facts.get("B").get("orderId").asLong();
        String eventA = facts.get("A").get("eventId").asText();
        String eventB = facts.get("B").get("eventId").asText();
        W6GapRecovery recovery = new W6GapRecovery(jdbcTemplate, orderJpaRepository, orderOutboxPort, transactionTemplate, objectMapper);

        // 출발점: 1막이 남긴 그대로다 — W7 Outbox 비어 있음, A 활동 0, B 활동 있음
        Map<String, Object> pgBeforeRecovery = pgStats();
        Snapshot before = snapshot(orderA, orderB);
        assertThat(count("SELECT COUNT(*) FROM order_outbox")).isZero();
        assertThat(before.activityA()).isEmpty();
        assertThat(before.activityB()).isNotEmpty();

        // act 1: 첫 복구 → relay 한 회차
        Result firstA = recovery.recover(orderA);
        Result firstB = recovery.recover(orderB);
        assertThat(orderOutboxPort.findByEventId(eventA).orElseThrow().getPayload()).isEqualTo(facts.get("A").get("payload").asText());
        assertThat(orderOutboxPort.findByEventId(eventB).orElseThrow().getPayload()).isEqualTo(facts.get("B").get("payload").asText());
        RelayResult firstRelay = relay.relayOnce();

        // assert 1: A는 빠졌던 활동이 생기고(같은 eventId로 소비), B는 재발행이 와도 그대로
        await().atMost(Duration.ofSeconds(30)).until(() -> !activity(orderA).isEmpty());
        await().atMost(Duration.ofSeconds(30)).until(() -> count("SELECT COUNT(*) FROM consumed_order_confirmation WHERE event_id = ?", eventA) == 1);
        Thread.sleep(3_000); // B의 재발행이 consumer에 닿아 no-op으로 끝날 시간
        Snapshot afterFirst = snapshot(orderA, orderB);

        // act 2: 같은 대상의 두 번째 복구 → relay
        Result secondA = recovery.recover(orderA);
        Result secondB = recovery.recover(orderB);
        RelayResult secondRelay = relay.relayOnce();
        Thread.sleep(3_000);
        Snapshot afterSecond = snapshot(orderA, orderB);
        Map<String, Object> pgAfterRecovery = pgStats();

        // 기록
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstRecovery", Map.of("A", firstA, "B", firstB, "relay", firstRelay));
        result.put("secondRecovery", Map.of("A", secondA, "B", secondB, "relay", secondRelay));
        result.put("outbox", jdbcTemplate.queryForList("SELECT event_id AS eventId, order_id AS orderId, status, attempt_count AS attempts, published_partition AS publishedPartition, published_offset AS publishedOffset FROM order_outbox ORDER BY id"));
        result.put("before", before);
        result.put("afterFirst", afterFirst);
        result.put("afterSecond", afterSecond);
        result.put("pg", Map.of("beforePreparation", facts.get("pgBefore"), "afterPreparation", facts.get("pgAfterPreparation"),
            "beforeRecovery", pgBeforeRecovery, "afterRecovery", pgAfterRecovery));
        Files.writeString(Path.of(System.getenv("C7_RESULT")), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("[C-7 복구] " + objectMapper.writeValueAsString(result));

        // assert: 첫 복구는 두 행을 만들고 relay가 둘 다 보냈다. 두 번째 복구는 아무것도 늘리지 않는다
        assertThat(firstA).isEqualTo(Result.CREATED);
        assertThat(firstB).isEqualTo(Result.CREATED);
        assertThat(firstRelay).isEqualTo(new RelayResult(2, 2, null));
        assertThat(secondA).isEqualTo(Result.ALREADY_PRESENT);
        assertThat(secondB).isEqualTo(Result.ALREADY_PRESENT);
        assertThat(secondRelay).isEqualTo(new RelayResult(0, 0, null));
        assertThat(count("SELECT COUNT(*) FROM order_outbox")).isEqualTo(2);

        // A: 빠졌던 활동 = 예약(품목)과 같은 상품·수량. B: 활동 행·수량·소비 기록이 처음과 같다
        assertThat(afterFirst.activityA()).isEqualTo(before.reservationsQuantitiesA());
        assertThat(afterFirst.activityB()).isEqualTo(before.activityB());
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(afterFirst.consumed()).containsExactlyInAnyOrder(eventA, eventB);

        // C-8: 복구 동안 PG 호출 0 · 주문 재확정 없음(상태·확정 시각 그대로) · 예약 추가 소비 없음
        assertThat(pgAfterRecovery.get("create")).isEqualTo(pgBeforeRecovery.get("create"));
        assertThat(pgAfterRecovery.get("lookup")).isEqualTo(pgBeforeRecovery.get("lookup"));
        assertThat(pgAfterRecovery.get("callback_sent")).isEqualTo(pgBeforeRecovery.get("callback_sent"));
        assertThat(afterSecond.orders()).isEqualTo(before.orders());
        assertThat(afterSecond.reservations()).isEqualTo(before.reservations());
        assertThat(afterSecond.payments()).isEqualTo(before.payments());
    }

    record Snapshot(
        List<Map<String, Object>> orders,
        List<Map<String, Object>> payments,
        List<Map<String, Object>> reservations,
        List<Map<String, Object>> reservationsQuantitiesA,
        List<Map<String, Object>> activityA,
        List<Map<String, Object>> activityB,
        List<String> consumed
    ) {}

    private Snapshot snapshot(long orderA, long orderB) {
        return new Snapshot(
            jdbcTemplate.queryForList("SELECT id, status, CAST(confirmed_at AS CHAR) AS confirmedAt FROM orders WHERE id IN (?, ?) ORDER BY id", orderA, orderB),
            jdbcTemplate.queryForList("SELECT order_id AS orderId, status, transaction_key AS transactionKey FROM payments WHERE order_id IN (?, ?) ORDER BY order_id", orderA, orderB),
            jdbcTemplate.queryForList("SELECT order_id AS orderId, product_id AS productId, quantity, status FROM stock_reservations WHERE order_id IN (?, ?) ORDER BY order_id, product_id", orderA, orderB),
            jdbcTemplate.queryForList("SELECT product_id AS productId, quantity FROM stock_reservations WHERE order_id = ? ORDER BY product_id", orderA),
            activity(orderA),
            activity(orderB),
            jdbcTemplate.queryForList("SELECT event_id FROM consumed_order_confirmation ORDER BY id", String.class)
        );
    }

    private List<Map<String, Object>> activity(long orderId) {
        return jdbcTemplate.queryForList("SELECT product_id AS productId, quantity FROM product_purchase_activity WHERE order_id = ? ORDER BY product_id", orderId);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pgStats() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(PG_SIM + "/_stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), Map.class);
    }
}
