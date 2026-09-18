package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false, updatable = false)
    private Brand brand;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    @Embedded
    private ProductStock stock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    protected Product() {
    }

    public Product(Brand brand, String name, long price, int stockQuantity) {
        this.brand = Objects.requireNonNull(brand, "brand");
        this.name = validatedName(name);
        validatePrice(price);
        this.price = price;
        this.stock = new ProductStock(stockQuantity);
    }

    public Long getId() {
        return id;
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

    public int getStockQuantity() {
        return stock.getQuantity();
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public ZonedDateTime getUpdatedAt() {
        return updatedAt;
    }

    public ZonedDateTime getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void update(String name, long price) {
        requireActive();
        String newName = validatedName(name);
        validatePrice(price);
        this.name = newName;
        this.price = price;
    }

    public void changeStockQuantityTo(int quantity) {
        requireActive();
        stock.changeQuantityTo(quantity);
    }

    public void deductStock(int quantity) {
        requireActive();
        stock.deduct(quantity);
    }

    public void validateStockDeduction(int quantity) {
        requireActive();
        stock.validateDeduction(quantity);
    }

    public void delete(ZonedDateTime timestamp) {
        if (!isDeleted()) {
            this.deletedAt = Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    private void requireActive() {
        if (isDeleted()) {
            throw new ProductException(ProductException.Reason.DELETED_PRODUCT);
        }
    }

    private static String validatedName(String input) {
        if (input == null) {
            throw new ProductException(ProductException.Reason.INVALID_NAME);
        }
        String name = input.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
            throw new ProductException(ProductException.Reason.INVALID_NAME);
        }
        return name;
    }

    private static void validatePrice(long price) {
        if (price < 1) {
            throw new ProductException(ProductException.Reason.INVALID_PRICE);
        }
    }

    @PrePersist
    private void prePersist() {
        ZonedDateTime now = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
