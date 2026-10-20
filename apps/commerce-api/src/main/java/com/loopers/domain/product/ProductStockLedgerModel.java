package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-03 포함 개념 재고 원장 (TB-11 product_stock_ledger, DR-38). 재고의 진실이며 append-only 다. 만든 뒤 고치거나 지우지 않는다.
 * 상품별 quantity 합 = TB-10 available. 행은 {@link ProductStockModel} 의 변경 메서드만 만든다.
 * (order_id, product_id, type) 고유: 한 주문의 한 상품은 예약·반환이 각각 최대 한 번이다 (INV-08). ADJUSTMENT 는 order_id 가 NULL 이라 걸리지 않는다.
 */
@Entity
@Table(name = "product_stock_ledger",
    uniqueConstraints = @UniqueConstraint(name = "ux_product_stock_ledger_order", columnNames = {"order_id", "product_id", "type"}))
public class ProductStockLedgerModel extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private StockLedgerType type;

    /** 부호 있는 변동량. 입고 +, 차감 −. 0 인 행은 만들지 않는다. */
    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    /** 참조 개념 주문 (BC-04). ID 만. RESERVE·RELEASE 만 가진다. */
    @Column(name = "order_id", updatable = false)
    private Long orderId;

    protected ProductStockLedgerModel() {}

    private ProductStockLedgerModel(Long productId, StockLedgerType type, int quantity, Long orderId) {
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.orderId = orderId;
    }

    static ProductStockLedgerModel adjustment(Long productId, int delta) {
        return new ProductStockLedgerModel(productId, StockLedgerType.ADJUSTMENT, delta, null);
    }

    static ProductStockLedgerModel reserve(Long productId, Long orderId, int quantity) {
        return new ProductStockLedgerModel(productId, StockLedgerType.RESERVE, -quantity, orderId);
    }

    static ProductStockLedgerModel release(Long productId, Long orderId, int quantity) {
        return new ProductStockLedgerModel(productId, StockLedgerType.RELEASE, quantity, orderId);
    }

    public Long getProductId() {
        return productId;
    }

    public StockLedgerType getType() {
        return type;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getOrderId() {
        return orderId;
    }
}
