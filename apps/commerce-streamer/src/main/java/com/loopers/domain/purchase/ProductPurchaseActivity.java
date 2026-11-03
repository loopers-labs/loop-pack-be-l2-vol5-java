package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 확정 주문의 상품별 구매 사실 (설계 D10). orderId·productId 당 1행이며 수량·확정 시각을 보존한다.
 * productId 는 사실의 식별자로만 보관하고 현재 상품 행·가격을 조회하지 않는다 (과제 p.4).
 */
@Getter
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_ppa_order_product", columnNames = {"order_id", "product_id"})
)
public class ProductPurchaseActivity extends BaseEntity {

    private Long orderId;

    private Long productId;

    private int quantity;

    private ZonedDateTime confirmedAt;

    protected ProductPurchaseActivity() {}

    public ProductPurchaseActivity(Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt) {
        if (orderId == null) {
            throw new IllegalArgumentException("구매 활동의 orderId 는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("구매 활동의 productId 는 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("구매 수량은 1개 이상이어야 합니다.");
        }
        if (confirmedAt == null) {
            throw new IllegalArgumentException("구매 활동의 confirmedAt 은 필수입니다.");
        }
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }
}
