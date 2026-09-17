package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 100;
    private static final long MIN_PRICE = 1L;
    private static final long MAX_PRICE = 100_000_000L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, updatable = false)
    private Brand brand;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "stock", nullable = false)
    private StockQuantity stock;

    protected Product() {}

    private Product(Brand brand, String name, long price) {
        this.brand = brand;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = new StockQuantity(0L);
    }

    public static Product create(Brand brand, String name, long price) {
        return new Product(brand, name, price);
    }

    public Brand getBrand() {
        return brand;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public StockQuantity getStock() {
        return stock;
    }

    public void changeStockTo(long quantity) {
        this.stock = new StockQuantity(quantity);
    }

    public void updateDetails(String name, long price) {
        String validatedName = validateName(name);
        long validatedPrice = validatePrice(price);
        this.name = validatedName;
        this.price = validatedPrice;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 100자 이하여야 합니다.");
        }
        return name;
    }

    private static long validatePrice(long price) {
        if (price < MIN_PRICE || price > MAX_PRICE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 가격은 1원 이상 100,000,000원 이하여야 합니다.");
        }
        return price;
    }
}
