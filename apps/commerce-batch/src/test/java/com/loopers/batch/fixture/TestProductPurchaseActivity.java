package com.loopers.batch.fixture;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * batch 테스트 전용 — 원본 구매 활동 테이블 (W9 D-2).
 * 운영에서는 commerce-streamer가 이 테이블을 만들고 행을 쌓는다 (출처: streamer {@code com.loopers.purchase.domain.ProductPurchaseActivity}).
 * batch 운영 코드는 SQL로 읽기만 하므로 이 클래스는 테스트 DB에 테이블을 만들고 given에서 원본 행을 넣는 용도뿐이다.
 * streamer 엔티티의 컬럼·제약이 바뀌면 함께 고친다.
 */
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_purchase_activity_product_confirmed", columnList = "product_id, confirmed_at")
)
public class TestProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    protected TestProductPurchaseActivity() {}

    private TestProductPurchaseActivity(Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
        this.eventId = UUID.randomUUID().toString();
    }

    /**
     * @param confirmedAtUtc 예: "2026-08-17T01:00:00Z"
     */
    public static TestProductPurchaseActivity of(long orderId, long productId, int quantity, String confirmedAtUtc) {
        return new TestProductPurchaseActivity(orderId, productId, quantity, ZonedDateTime.parse(confirmedAtUtc));
    }

    public TestProductPurchaseActivity deleted() {
        delete();
        return this;
    }
}
