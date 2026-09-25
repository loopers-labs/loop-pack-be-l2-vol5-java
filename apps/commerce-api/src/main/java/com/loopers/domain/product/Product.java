package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;

public class Product {

    private static final int MAX_NAME_LENGTH = 100;
    private static final long MIN_PRICE = 1L;
    private static final long MAX_PRICE = 100_000_000L;

    private final Long id;
    private final Long brandId;
    private final ZonedDateTime createdAt;
    private String name;
    private long price;
    private StockQuantity stock;
    private ZonedDateTime deletedAt;

    private Product(
        Long id,
        Long brandId,
        String name,
        long price,
        StockQuantity stock,
        ZonedDateTime createdAt,
        ZonedDateTime deletedAt
    ) {
        this.id = id;
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = stock;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    public static Product create(Long brandId, String name, long price) {
        return new Product(null, brandId, name, price, new StockQuantity(0L), null, null);
    }

    public static Product reconstitute(
        Long id,
        Long brandId,
        String name,
        long price,
        long stock,
        ZonedDateTime createdAt,
        ZonedDateTime deletedAt
    ) {
        return new Product(id, brandId, name, price, new StockQuantity(stock), createdAt, deletedAt);
    }

    public Long getId() {
        return id;
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

    public StockQuantity getStock() {
        return stock;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public ZonedDateTime getDeletedAt() {
        return deletedAt;
    }

    public void changeStockTo(long quantity) {
        this.stock = new StockQuantity(quantity);
    }

    public void decreaseStock(int quantity) {
        this.stock = stock.decrease(quantity);
    }

    public void updateDetails(String name, long price) {
        String validatedName = validateName(name);
        long validatedPrice = validatePrice(price);
        this.name = validatedName;
        this.price = validatedPrice;
    }

    public void delete() {
        if (deletedAt == null) {
            deletedAt = ZonedDateTime.now();
        }
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
