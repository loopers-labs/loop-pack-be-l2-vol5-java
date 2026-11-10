package com.loopers.outbox.c7;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 격리 실행(run.sh)의 막들이 같은 방식으로 읽는 관찰 값. 막 사이에 상태를 넘길 때 같은 모양이어야 비교가 된다.
 */
final class IsolatedFacts {

    private IsolatedFacts() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> pgStats(ObjectMapper objectMapper) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(System.getenv("C7_PG_URL") + "/_stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> stats = objectMapper.readValue(response.body(), Map.class);
        return new LinkedHashMap<>(Map.of("create", stats.get("create"), "lookup", stats.get("lookup"), "callback_sent", stats.get("callback_sent")));
    }

    /** 주문 하나의 업무 상태 — 재확정·예약 추가 소비/반환·재고·포인트 변화가 없었는지 막 사이에 비교한다 */
    static Map<String, Object> business(JdbcTemplate jdbc, long orderId, long userId, List<Long> productIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", jdbc.queryForMap("SELECT status, CAST(confirmed_at AS CHAR) AS confirmedAt FROM orders WHERE id = ?", orderId));
        m.put("payments", jdbc.queryForList("SELECT status, transaction_key AS transactionKey FROM payments WHERE order_id = ?", orderId));
        m.put("reservations", jdbc.queryForList("SELECT product_id AS productId, quantity, status FROM stock_reservations WHERE order_id = ? ORDER BY product_id", orderId));
        m.put("stock", jdbc.queryForList("SELECT id, stock FROM products WHERE id IN (?, ?) ORDER BY id", productIds.get(0), productIds.get(1)));
        m.put("pointGroups", jdbc.queryForList("SELECT id, remaining FROM point_groups WHERE user_id = ? ORDER BY id", userId));
        m.put("pointHistories", jdbc.queryForObject("SELECT COUNT(*) FROM point_histories WHERE user_id = ?", Long.class, userId));
        return m;
    }

    static List<Map<String, Object>> activity(JdbcTemplate jdbc, long orderId) {
        return jdbc.queryForList("SELECT product_id AS productId, quantity FROM product_purchase_activity WHERE order_id = ? ORDER BY product_id", orderId);
    }
}
