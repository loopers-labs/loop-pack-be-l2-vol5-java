package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_products_brand_deleted_price", columnList = "brand_id, deleted_at, price"),
    @Index(name = "idx_products_brand_deleted_id",    columnList = "brand_id, deleted_at, id")
})
public class ProductModel extends BaseEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "price", nullable = false)
    private Long price;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Column(name = "reserved_stock", nullable = false)
    private int reservedStock;

    protected ProductModel() {
    }

    public ProductModel(Long brandId, String name, Long price, int stock) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드는 비어있을 수 없습니다.");
        }
        if (stock < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다.");
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = stock;
        this.reservedStock = 0;
    }

    public Long getBrandId() {
        return brandId;
    }

    public String getName() {
        return name;
    }

    public Long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public int getReservedStock() {
        return reservedStock;
    }

    public int getAvailableStock() {
        return stock - reservedStock;
    }

    public void update(String name, Long price) {
        this.name = validateName(name);
        this.price = validatePrice(price);
    }

    /** 관리자가 가용 재고를 직접 설정한다. 예약 수량은 유지된다. */
    public void changeStock(int availableQuantity) {
        if (availableQuantity < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다. (요청: " + availableQuantity + ")");
        }
        this.stock = availableQuantity + reservedStock;
    }

    /** PG 호출 전 재고를 예약한다. */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "예약 수량은 양수여야 합니다.");
        }
        if (quantity > getAvailableStock()) {
            throw new CoreException(
                ErrorType.BAD_REQUEST,
                "재고가 부족합니다. (가용: " + getAvailableStock() + ", 요청: " + quantity + ")"
            );
        }
        this.reservedStock += quantity;
    }

    /** PG 성공 시 예약을 소비한다. 실제 재고도 함께 차감된다. */
    public void consumeReservation(int quantity) {
        if (quantity > reservedStock) {
            throw new CoreException(ErrorType.BAD_REQUEST, "예약 수량이 부족합니다.");
        }
        this.stock -= quantity;
        this.reservedStock -= quantity;
    }

    /** PG 실패·취소 시 예약을 반환한다. */
    public void releaseReservation(int quantity) {
        if (quantity > reservedStock) {
            throw new CoreException(ErrorType.BAD_REQUEST, "예약 수량이 부족합니다.");
        }
        this.reservedStock -= quantity;
    }

    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 양수여야 합니다. (요청: " + quantity + ")");
        }
        if (quantity > stock) {
            throw new CoreException(
                ErrorType.BAD_REQUEST,
                "재고가 부족합니다. (재고: " + stock + ", 요청: " + quantity + ")"
            );
        }
        this.stock -= quantity;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return name;
    }

    private Long validatePrice(Long price) {
        if (price == null || price <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "가격은 양의 정수여야 합니다.");
        }
        return price;
    }


}
