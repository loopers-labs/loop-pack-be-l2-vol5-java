package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    public static final int MAX_NAME_LENGTH = 100;

    private Long brandId;

    private String name;

    @Embedded
    private Price price;

    @Embedded
    private Stock stock;

    protected Product() {}

    public Product(Long brandId, String name, Price price) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품의 브랜드 식별자는 필수입니다.");
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.stock = new Stock(0);
        changePrice(price);
    }

    /**
     * 이름과 가격만 수정한다. 브랜드는 고정이고 재고는 별도 경로로 변경한다.
     * 검증에 실패하면 기존 값을 그대로 유지한다.
     */
    public void update(String name, Price price) {
        String validatedName = validateName(name);
        changePrice(price);
        this.name = validatedName;
    }

    public void changePrice(Price price) {
        if (price == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 가격은 필수입니다.");
        }
        this.price = price;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다.");
        }
        return name;
    }

    /**
     * 증감이 아니라 최종 수량으로 설정한다.
     */
    public void changeStock(int quantity) {
        stock.changeQuantity(quantity);
    }

    public void deductStock(int quantity) {
        stock.deduct(quantity);
    }
}
