package com.loopers.domain.product;

import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;

public final class Product {
    private final ProductId id;
    private final BrandId brandId;
    private String name;
    private Money price;
    private Stock stock;
    private boolean deleted;

    private Product(ProductId id, BrandId brandId, String name, Money price, Stock stock, boolean deleted) {
        validateName(name);
        java.util.Objects.requireNonNull(brandId);
        java.util.Objects.requireNonNull(price);
        java.util.Objects.requireNonNull(stock);
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }

    public static Product create(BrandId brandId, String name, Money price, Stock stock) {
        return new Product(null, brandId, name, price, stock, false);
    }

    public static Product restore(ProductId id, BrandId brandId, String name, Money price, Stock stock, boolean deleted) {
        java.util.Objects.requireNonNull(id);
        return new Product(id, brandId, name, price, stock, deleted);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("상품 이름은 공백이 아닌 1~100자여야 합니다.");
        }
    }

    private void requireActive() {
        if (deleted) {
            throw new IllegalStateException("삭제된 상품은 변경할 수 없습니다.");
        }
    }

    public void change(String name, Money price) {
        requireActive();
        validateName(name);
        java.util.Objects.requireNonNull(price);
        this.name = name;
        this.price = price;
    }

    public void setStock(Stock stock) {
        requireActive();
        java.util.Objects.requireNonNull(stock);
        this.stock = stock;
    }

    public void decreaseStock(int quantity) {
        requireActive();
        stock = stock.decrease(quantity);
    }

    public void delete() {
        deleted = true;
    }

    public ProductId getId() { return id; }
    public BrandId getBrandId() { return brandId; }
    public String getName() { return name; }
    public Money getPrice() { return price; }
    public Stock getStock() { return stock; }
    public boolean isDeleted() { return deleted; }
}
