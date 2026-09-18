package com.loopers.product.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    private Long brandId;
    private String name;
    private long price;
    @Embedded
    private Stock stock;

    protected Product() {
    }

    public Product(Long brandId, String name, long price) {
        if (brandId == null) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = new Stock(0);
    }

    public Long getBrandId() {
        return brandId;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public Stock getStock() {
        return stock;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    public void update(String name, long price, Long brandId) {
        ensureActive();
        if (!this.brandId.equals(brandId)) {
            throw new CoreException(ErrorCode.BRAND_CHANGE_NOT_ALLOWED);
        }
        String validatedName = validateName(name);
        long validatedPrice = validatePrice(price);
        this.name = validatedName;
        this.price = validatedPrice;
    }

    public void changeStock(int quantity) {
        ensureActive();
        this.stock = new Stock(quantity);
    }

    public void decreaseStock(int quantity) {
        ensureActive();
        this.stock = stock.decrease(quantity);
    }

    @Override
    public void delete() {
        ensureActive();
        super.delete();
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new CoreException(ErrorCode.INVALID_PRODUCT_NAME);
        }
        String trimmedName = name.trim();
        if (trimmedName.isEmpty() || trimmedName.length() > 100) {
            throw new CoreException(ErrorCode.INVALID_PRODUCT_NAME);
        }
        return trimmedName;
    }

    private static long validatePrice(long price) {
        if (price < 1 || price > 1_000_000_000L) {
            throw new CoreException(ErrorCode.INVALID_PRODUCT_PRICE);
        }
        return price;
    }

    private void ensureActive() {
        if (isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }
}
