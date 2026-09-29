package com.loopers.domain.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Quantity;

public class Product {

    private final Long id;
    private final Long brandId;
    private ProductName name;
    private Price price;
    private Quantity quantity;
    private boolean deleted;

    private Product(Long id, Long brandId, ProductName name, Price price, Quantity quantity, boolean deleted) {
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.quantity = quantity;
        this.deleted = deleted;
    }

    public static Product register(Long brandId, String name, Price price) {
        if (brandId == null) {
            throw new IllegalArgumentException("brandId 는 필수입니다");
        }
        return new Product(null, brandId, ProductName.of(name), guardPrice(price), Quantity.ZERO, false);
    }

    public static Product restore(
        Long id, Long brandId, String name, Price price, Quantity quantity, boolean deleted) {
        return new Product(id, brandId, ProductName.of(name), price, quantity, deleted);
    }

    public void update(String name, Price price) {
        if (deleted) {
            throw new DomainException(DomainError.PRODUCT_ALREADY_DELETED);
        }
        ProductName newName = ProductName.of(name);
        Price newPrice = guardPrice(price);
        this.name = newName;
        this.price = newPrice;
    }

    public void adjustTo(Quantity quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("수량은 필수입니다");
        }
        this.quantity = quantity;
    }

    public void deduct(Quantity amount) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("차감량은 양수여야 합니다");
        }
        if (quantity.isLessThan(amount)) {
            throw new DomainException(DomainError.INSUFFICIENT_STOCK);
        }
        this.quantity = quantity.minus(amount);
    }

    public boolean isSoldOut() {
        return quantity.isZero();
    }

    public void delete() {
        this.deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Long getId() {
        return id;
    }

    public Long getBrandId() {
        return brandId;
    }

    public String getName() {
        return name.value();
    }

    public Price getPrice() {
        return price;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    private static Price guardPrice(Price price) {
        if (price == null) {
            throw new IllegalArgumentException("가격은 필수입니다");
        }
        return price;
    }
}
