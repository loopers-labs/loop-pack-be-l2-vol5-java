package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Optional;

/**
 * AG-03 포함 개념 현재 재고 (TB-10 product_stock, DR-38). 상품당 한 행이며 상품 생성 때 0 으로 만들어진다.
 * 진실은 원장(TB-11)이고 available 은 그 합이다. 바꾸는 메서드는 available 과 함께 원장 행을 만들어 돌려주며, 호출자는 같은 트랜잭션에서 저장한다.
 * 바꾸는 쪽은 이 행을 비관적 쓰기 락으로 읽은 뒤에만 바꾼다 (DR-34, DR-38). 변경 감지로 저장하므로 락 없이 읽은 엔티티를 고치면 다른 트랜잭션의 차감을 덮는다.
 */
@Entity
@Table(name = "product_stock",
    uniqueConstraints = @UniqueConstraint(name = "ux_product_stock_product", columnNames = "product_id"))
public class ProductStockModel extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** INV-03 ≥ 0. */
    @Column(name = "available", nullable = false)
    private Integer available;

    protected ProductStockModel() {}

    public ProductStockModel(Long productId) {
        this.productId = productId;
        this.available = 0;
    }

    /** INV-03 재고 ≥ 0 (ASM-17). ER-21 INVALID_STOCK. */
    private static int validateStock(Integer stock) {
        if (stock == null || stock < 0) {
            throw new CoreException(ErrorType.INVALID_STOCK, "재고는 0 이상이어야 합니다. [stock = " + stock + "]");
        }
        return stock;
    }

    /** FR-ADMIN-PRODUCT-02/06: 최종 수량으로 맞춘다. 차이만큼 ADJUSTMENT 행. 같은 수량이면 행이 없다. */
    public Optional<ProductStockLedgerModel> adjustTo(Integer stock) {
        int target = validateStock(stock);
        int delta = target - available;
        if (delta == 0) {
            return Optional.empty();
        }
        this.available = target;
        return Optional.of(ProductStockLedgerModel.adjustment(productId, delta));
    }

    /** FR-ORDER-02: available −= 수량. INV-03 위반이면 ER-16 INSUFFICIENT_STOCK (message 에 부족한 productId). */
    public ProductStockLedgerModel reserve(Long orderId, int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.INVALID_QUANTITY, "차감 수량은 양수여야 합니다. [quantity = " + quantity + "]");
        }
        if (available < quantity) {
            throw new CoreException(ErrorType.INSUFFICIENT_STOCK,
                "재고가 부족합니다. [productId = " + productId + ", stock = " + available + ", quantity = " + quantity + "]");
        }
        this.available -= quantity;
        return ProductStockLedgerModel.reserve(productId, orderId, quantity);
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getAvailable() {
        return available;
    }
}
