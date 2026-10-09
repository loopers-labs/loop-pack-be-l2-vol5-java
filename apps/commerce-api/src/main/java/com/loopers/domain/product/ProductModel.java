package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "product", indexes = @Index(name = "idx_product_brand_deleted", columnList = "brand_id, deleted_at"))
public class ProductModel extends BaseEntity {

    @Column(nullable = false)
    private Long brandId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    @Embedded
    private Stock stock;

    protected ProductModel() {}

    public ProductModel(Long brandId, String name, long price, int initialStockQuantity) {
        if (brandId == null || brandId <= 0) {
            throw new IllegalArgumentException("브랜드 ID는 양수여야 합니다.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("상품 가격은 양수여야 합니다.");
        }
        this.brandId = brandId;
        this.name = validateAndNormalizeName(name);
        this.price = price;
        this.stock = new Stock(initialStockQuantity);
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

    public int getStockQuantity() {
        return stock.getQuantity();
    }

    public void decreaseStock(int quantity) {
        this.stock = stock.decrease(quantity);
    }

    public void changeStock(int quantity) {
        this.stock = stock.changeTo(quantity);
    }

    public void update(String name, long price) {
        String normalizedName = validateAndNormalizeName(name);
        if (price <= 0) {
            throw new IllegalArgumentException("상품 가격은 양수여야 합니다.");
        }
        this.name = normalizedName;
        this.price = price;
    }

    public void markDeleted() {
        delete();
    }

    private static String validateAndNormalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("상품명은 비어있을 수 없습니다.");
        }
        String normalizedName = name.strip();
        if (lengthOf(normalizedName) > 100) {
            throw new IllegalArgumentException("상품명은 100자 이하여야 합니다.");
        }
        return normalizedName;
    }

    private static int lengthOf(String value) {
        return value.codePointCount(0, value.length());
    }
}
