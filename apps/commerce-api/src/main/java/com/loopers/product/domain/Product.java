package com.loopers.product.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import jakarta.persistence.Table;

@Entity
@Table(name = "product",
    indexes = {
        @Index(name = "idx_product_likes_filter", columnList = "deleted_at,id,brand_id"),
        @Index(name = "idx_product_latest", columnList = "deleted_at,created_at DESC,id ASC"),
        @Index(name = "idx_product_brand_latest", columnList = "brand_id,deleted_at,created_at DESC,id ASC"),
        @Index(name = "idx_product_price", columnList = "deleted_at,price ASC,id ASC"),
        @Index(name = "idx_product_brand_price", columnList = "brand_id,deleted_at,price ASC,id ASC")
    },
    uniqueConstraints = @UniqueConstraint(
    name = "uk_product_active_name", columnNames = {"brand_id", "active_name_key"}))
@Check(name = "ck_product_name", constraints = "octet_length(convert(name using utf16)) between 2 and 200"
    + " and ascii(left(name, 1)) > 32 and ascii(right(name, 1)) > 32")
public class Product extends BaseEntity {

    @Column(nullable = false)
    private Long brandId;
    @Column(nullable = false, length = 100)
    private String name;

    // Deleted names are reusable; binary keys preserve Java String.equals semantics.
    @Column(name = "active_name_key", insertable = false, updatable = false,
        columnDefinition = "varbinary(400) generated always as"
            + " (case when deleted_at is null then convert(name using binary) else null end) virtual")
    private byte[] activeNameKey;
    @Column(nullable = false)
    @Check(name = "ck_product_price", constraints = "price between 1 and 1000000000")
    private long price;
    protected Product() {
    }

    public Product(Long brandId, String name, long price) {
        if (brandId == null) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
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
