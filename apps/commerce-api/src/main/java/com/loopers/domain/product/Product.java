package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 100;
    private static final long MAX_PRICE = 10_000_000L;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    private String name;
    private Long price;
    private Long stock;

    protected Product() {}

    public Product(Long brandId, String name, Long price, Long stock) {
        if (brandId == null) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "브랜드는 필수입니다.");
        }
        validateName(name);
        validatePrice(price);
        validateStock(stock);
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "상품 이름은 " + MAX_NAME_LENGTH + "자 이하여야 합니다.");
        }
    }

    private static void validatePrice(Long price) {
        if (price == null || price < 0 || price > MAX_PRICE) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "상품 가격은 0원 이상 " + MAX_PRICE + "원 이하여야 합니다.");
        }
    }

    private static void validateStock(Long stock) {
        if (stock == null || stock < 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "재고는 0 이상이어야 합니다.");
        }
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

    public Long getStock() {
        return stock;
    }

    public void update(String name, Long price) {
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void decreaseStock(long quantity) {
        if (quantity <= 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "차감할 재고 수량은 1 이상이어야 합니다.");
        }
        if (quantity > stock) {
            throw new DomainException(DomainErrorType.CONFLICT, "재고가 부족합니다.");
        }
        this.stock -= quantity;
    }

    public void changeStock(Long stock) {
        validateStock(stock);
        this.stock = stock;
    }
}
