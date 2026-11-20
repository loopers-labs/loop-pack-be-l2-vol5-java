package com.loopers.job.ranking;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 입력 원본 {@code product_purchase_activity}를 테스트 DB에 준비한다.
 * 실제 테이블은 commerce-streamer가 소유하므로 batch 테스트에서는 같은 열·제약으로 직접 만든다.
 * 테스트마다 다른 주·주문 ID를 써서, 다른 테스트의 행이나 Batch metadata를 지우지 않는다.
 * 경계 확인용 행은 앞뒤 주에 들어가므로, 그 이웃 주도 다른 테스트가 쓰지 않게 고른다.
 */
final class PurchaseActivityFixture {
    private final JdbcTemplate jdbc;

    PurchaseActivityFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS product_purchase_activity (
              id BIGINT NOT NULL AUTO_INCREMENT,
              created_at DATETIME(6) NOT NULL,
              updated_at DATETIME(6) NOT NULL,
              deleted_at DATETIME(6),
              order_id BIGINT NOT NULL,
              product_id BIGINT NOT NULL,
              quantity INT NOT NULL,
              confirmed_at DATETIME(6) NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_purchase_order_product (order_id, product_id)
            ) ENGINE=InnoDB
            """);
    }

    record Purchase(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {}

    static Purchase purchase(long orderId, long productId, int quantity, String confirmedAtUtc) {
        return new Purchase(orderId, productId, quantity, LocalDateTime.parse(confirmedAtUtc));
    }

    /** 상품별 SELECT를 사용하는 이전 Reader 실험과 마이그레이션 검증을 위한 기준 쿼리. */
    static long snapshotQuantity(JdbcTemplate jdbc, String snapshotId, long productId) {
        return jdbc.queryForObject(
            "SELECT SUM(quantity) FROM ranking_snapshot_input WHERE snapshot_id = ? AND product_id = ?",
            Long.class, snapshotId, productId);
    }

    void insert(Purchase... purchases) {
        jdbc.batchUpdate("""
            INSERT INTO product_purchase_activity
              (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
            VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?, ?, ?)
            """, List.of(purchases).stream()
            .map(p -> new Object[] {p.orderId(), p.productId(), p.quantity(), p.confirmedAtUtc()})
            .toList());
    }

    /** 저장 시각(created_at, UTC)을 정해서 넣는다. 늦은 구매 감지의 "최근 30분" 판단을 시험할 때 쓴다. */
    void insertStoredAt(LocalDateTime storedAtUtc, Purchase... purchases) {
        jdbc.batchUpdate("""
            INSERT INTO product_purchase_activity
              (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, List.of(purchases).stream()
            .map(p -> new Object[] {storedAtUtc, storedAtUtc, p.orderId(), p.productId(), p.quantity(), p.confirmedAtUtc()})
            .toList());
    }
}
