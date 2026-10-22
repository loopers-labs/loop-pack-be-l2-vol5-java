package com.loopers.infrastructure.product;

import com.loopers.domain.BaseEntity;
import com.loopers.infrastructure.brand.BrandJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "products",
    indexes = {
        @Index(name = "FKa3a4mpsfdf4d2y6r8ra3sc8mv", columnList = "brand_id"),
        @Index(name = "idx_products_brand_deleted_price", columnList = "brand_id, deleted_at, price")
    }
)
public class ProductJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, updatable = false)
    private BrandJpaEntity brand;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "stock", nullable = false)
    private long stock;

    private ProductJpaEntity(BrandJpaEntity brand, String name, long price, long stock) {
        this.brand = brand;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public static ProductJpaEntity create(BrandJpaEntity brand, String name, long price, long stock) {
        return new ProductJpaEntity(brand, name, price, stock);
    }

    public void updateDetails(String name, long price) {
        this.name = name;
        this.price = price;
    }

    public void changeStockTo(long stock) {
        this.stock = stock;
    }
}
