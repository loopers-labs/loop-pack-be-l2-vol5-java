package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 확정 주문의 상품별 구매 사실. 상품 테이블을 참조하지 않고 과거 주문의 productId·수량·확정 시각을 그대로 보존한다.
 */
@Getter
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}
    )
)
public class ProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    // JPA 가 사용하는 기본 생성자.
    protected ProductPurchaseActivity() {}

    public ProductPurchaseActivity(Long orderId, PurchaseLine line, ZonedDateTime confirmedAt) {
        this.orderId = orderId;
        this.productId = line.productId();
        this.quantity = line.quantity();
        this.confirmedAt = confirmedAt;
    }

    public PurchaseLine toLine() {
        return new PurchaseLine(productId, quantity);
    }
}
