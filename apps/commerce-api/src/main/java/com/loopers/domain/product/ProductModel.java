package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class ProductModel extends BaseEntity {

    // 이름 상한은 Brand와 동일한 원칙(DB 컬럼 크기에 맞춘 명시적 지정)을 따른다.
    private static final int NAME_MAX_LENGTH = 255;

    @Column(length = NAME_MAX_LENGTH)
    private String name;
    private Long price;
    private Long brandId;
    @Embedded
    @AttributeOverride(name = "remaining", column = @Column(name = "stock_remaining"))
    private Stock stock;

    protected ProductModel() {}

    public ProductModel(String name, Long price, Long brandId, int initialStock) {
        validateName(name);
        validatePrice(price);
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 비어있을 수 없습니다.");
        }
        this.name = name;
        this.price = price;
        this.brandId = brandId;
        this.stock = new Stock(initialStock);
    }

    /**
     * 이름·가격만 수정한다. brandId는 API 계약상 변경 대상이 아니다(design.md 5번 섹션).
     */
    public void update(String name, Long price) {
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void changeStock(int quantity) {
        stock.set(quantity);
    }

    /**
     * 주문 확정 시 재고를 상대적으로 차감한다 — 관리자 재고 조정(changeStock, 절대값 지정)과는 다른 동작이다.
     */
    public void decreaseStock(int quantity) {
        stock.decrease(quantity);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이름은 비어있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이름은 " + NAME_MAX_LENGTH + "자를 초과할 수 없습니다.");
        }
    }

    private static void validatePrice(Long price) {
        if (price == null || price < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "가격은 0 이상이어야 합니다.");
        }
    }

    public String getName() {
        return name;
    }

    public Long getPrice() {
        return price;
    }

    public Long getBrandId() {
        return brandId;
    }

    public int getRemainingStock() {
        return stock.remaining();
    }
}
