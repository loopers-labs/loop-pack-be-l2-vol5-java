package com.loopers.domain.catalog;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * AG-03 상품 (TB-03 product). 가격·재고를 포함 개념으로 가진다 (DR-03).
 * 상태 ST-02 는 deletedAt 의 NULL 여부 (DR-16). 전이: (없음) → ACTIVE, ACTIVE → DELETED (delete()).
 * 인덱스 IX-03 (latest), IX-04 (price_asc).
 */
@Entity
@Table(name = "product", indexes = {
    @Index(name = "ix_product_latest", columnList = "deleted_at, created_at, id"),
    @Index(name = "ix_product_price", columnList = "deleted_at, price, id")
})
public class ProductModel extends BaseEntity {
    public static final int NAME_MAX_LENGTH = 200;

    /** INV-11 소속 브랜드는 생성 후 불변. AG 간 참조라 FK 없음 (설계 3-3). */
    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "price", nullable = false)
    private Long price;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    protected ProductModel() {}

    public ProductModel(Long brandId, String name, Long price, Integer stock) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BRAND_NOT_FOUND, "브랜드가 지정되지 않았습니다.");
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = validateStock(stock);
    }

    /** INV-13 이름 1~200자 (ASM-04, DR-11). ER-19 INVALID_PRODUCT_NAME. */
    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_NAME, "상품 이름은 비어 있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_NAME, "상품 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return name;
    }

    /** INV-13 가격 0 이상 정수, 표현 범위 안 (ASM-04: 0원 허용). ER-20 INVALID_PRODUCT_PRICE. */
    private static Long validatePrice(Long price) {
        if (price == null || price < 0) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_PRICE, "상품 가격은 0 이상이어야 합니다. [price = " + price + "]");
        }
        return price;
    }

    /** INV-03 재고 ≥ 0 (ASM-17). ER-21 INVALID_STOCK. */
    private static Integer validateStock(Integer stock) {
        if (stock == null || stock < 0) {
            throw new CoreException(ErrorType.INVALID_STOCK, "재고는 0 이상이어야 합니다. [stock = " + stock + "]");
        }
        return stock;
    }

    /** FR-ADMIN-PRODUCT-04: 이름·가격만. 브랜드(INV-11)·재고는 바꾸지 않는다 (ASM-16). */
    public void update(String name, Long price) {
        String validName = validateName(name);
        Long validPrice = validatePrice(price);
        this.name = validName;
        this.price = validPrice;
    }

    /** FR-ADMIN-PRODUCT-06: 증감이 아니라 최종 수량 설정. */
    public void changeStock(Integer stock) {
        this.stock = validateStock(stock);
    }

    /** FR-ORDER-02: 재고 −= 수량. INV-03 위반이면 ER-16 INSUFFICIENT_STOCK (message 에 부족한 productId). */
    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.INVALID_QUANTITY, "차감 수량은 양수여야 합니다. [quantity = " + quantity + "]");
        }
        if (stock < quantity) {
            throw new CoreException(ErrorType.INSUFFICIENT_STOCK,
                "재고가 부족합니다. [productId = " + getId() + ", stock = " + stock + ", quantity = " + quantity + "]");
        }
        this.stock -= quantity;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
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

    public Integer getStock() {
        return stock;
    }
}
