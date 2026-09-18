package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.Getter;

@Getter
public class Product {
    private final Long id;
    private final long brandId;
    private String name;
    private long price;
    private int stock;
    private boolean deleted;

    private Product(Long id, long brandId, String name, long price, int stock, boolean deleted) {
        if (brandId <= 0 || stock < 0) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        validateInformation(name, price);
        this.id = id;
        this.brandId = brandId;
        this.name = name.strip();
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }
    public static Product create(long brandId, String name, long price) {
        return new Product(null, brandId, name, price, 0, false);
    }
    public static Product restore(long id, long brandId, String name, long price, int stock, boolean deleted) {
        return new Product(id, brandId, name, price, stock, deleted);
    }
    public void update(String name, long price) {
        requireActive();
        validateInformation(name, price);
        this.name = name.strip();
        this.price = price;
    }
    public void setStock(int stock) {
        requireActive();
        if (stock < 0) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        this.stock = stock;
    }
    public void deductStock(int quantity) {
        requireActive();
        if (quantity <= 0) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        if (quantity > stock) { throw new CoreException(ErrorType.INSUFFICIENT_STOCK); }
        stock -= quantity;
    }
    public void delete() { deleted = true; }
    public void requireActive() {
        if (deleted) { throw new CoreException(ErrorType.PRODUCT_NOT_FOUND); }
    }
    private static void validateInformation(String name, long price) {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 100 || price <= 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
