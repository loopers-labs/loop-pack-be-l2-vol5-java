package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class ProductModel extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 100;
    private static final long MAX_PRICE = 100_000_000L;

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    private String name;

    private Long price;

    @Embedded
    private Stock stock;

    protected ProductModel() {
    }

    public ProductModel(Long brandId, String name, Long price, int stock) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드는 비어있을 수 없습니다.");
        }
        validateName(name);
        validatePrice(price);
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.stock = new Stock(stock);
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다.");
        }
    }

    private void validatePrice(Long price) {
        if (price == null || price <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "가격은 0보다 커야 합니다.");
        }
        if (price > MAX_PRICE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "가격은 " + MAX_PRICE + "을 넘을 수 없습니다.");
        }
    }

    public void updateNameAndPrice(String name, Long price) {
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void changeStock(int quantity) {
        this.stock = new Stock(quantity);
    }

    public void decreaseStock(int quantity) {
        stock.decrease(quantity);
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
        return stock.getRemaining();
    }
}
