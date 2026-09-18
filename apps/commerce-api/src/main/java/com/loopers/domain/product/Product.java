package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 50;
    private static final long MAX_PRICE = 100_000_000L;

    // 응답 조합에 쓰지 않도록 getter 를 두지 않는다 (설계 D-36)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    private String name;
    private long price;
    private int stock;

    protected Product() {}

    // 브랜드 없이 존재할 수 없다는 불변식의 생성 입구를 지킨다 (BRD-02, 설계 2.3)
    public Product(Brand brand, String name, long price) {
        if (brand == null || brand.isDeleted()) {
            throw new CoreException(BrandErrorCode.BRAND_NOT_FOUND);
        }
        validateName(name);
        validatePrice(price);
        this.brand = brand;
        this.name = name;
        this.price = price;
        this.stock = 0;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    // 브랜드는 바꾸지 않는다 (PRD-03)
    public void update(String name, long price) {
        validateNotDeleted();
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void changeStock(int stock) {
        validateNotDeleted();
        updateStock(stock);
    }

    public boolean canDecrease(int quantity) {
        return quantity > 0 && quantity <= stock;
    }

    public void decrease(int quantity) {
        if (!canDecrease(quantity)) {
            throw new CoreException(ProductErrorCode.OUT_OF_STOCK);
        }
        updateStock(stock - quantity);
    }

    // 재고를 바꾸는 모든 행동이 거치는 한 곳. "재고는 0 이상"(PRD-05)을 여기서만 지킨다.
    private void updateStock(int newStock) {
        if (newStock < 0) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
        this.stock = newStock;
    }

    private void validateNotDeleted() {
        if (isDeleted()) {
            throw new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    // 앞뒤 공백을 자르지 않는다. 길이에는 공백이 포함된다 (설계 6.5)
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ProductErrorCode.INVALID_PRODUCT_NAME);
        }
    }

    private static void validatePrice(long price) {
        if (price < 0 || price > MAX_PRICE) {
            throw new CoreException(ProductErrorCode.INVALID_PRICE);
        }
    }
}
