package com.loopers.job.weeklyranking;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** W6 consumer 가 저장한 것과 같은 모양(UTC 시각)으로 구매 활동 행을 넣는다. */
final class PurchaseActivityRows {

    private PurchaseActivityRows() {}

    static void insert(JdbcTemplate jdbc, long orderId, long productId, int quantity, String confirmedAtUtc) {
        LocalDateTime utc = LocalDateTime.ofInstant(Instant.parse(confirmedAtUtc), ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO product_purchase_activity
              (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))""", orderId, productId, quantity, utc);
    }
}
