package com.loopers.domain.purchaseactivity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.ZonedDateTime;

/**
 * 주문·상품별 구매 수량의 과거 사실(소비 설계 3절). 상품의 현재 존재·가격·노출과 연결하지 않으며 Product FK 도 두지 않는다.
 * 생성 뒤 수정 경로가 없다.
 */
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_ppa_order_product", columnNames = {"order_id", "product_id"})
)
@Check(name = "ck_ppa_order_id", constraints = "order_id > 0")
@Check(name = "ck_ppa_product_id", constraints = "product_id > 0")
@Check(name = "ck_ppa_quantity", constraints = "quantity > 0")
@Getter
public class ProductPurchaseActivityModel extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Long quantity;

    /** 연결 완료 사건과 같은 확정 순간. */
    @Column(name = "confirmed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private ZonedDateTime confirmedAt;

    protected ProductPurchaseActivityModel() {}

    private ProductPurchaseActivityModel(OrderConfirmation event, OrderConfirmation.Item item) {
        this.orderId = event.orderId();
        this.productId = item.productId();
        this.quantity = item.quantity();
        this.confirmedAt = event.confirmedAt();
    }

    public static ProductPurchaseActivityModel purchased(OrderConfirmation event, OrderConfirmation.Item item) {
        return new ProductPurchaseActivityModel(event, item);
    }
}
