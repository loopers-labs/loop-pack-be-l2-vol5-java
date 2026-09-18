package com.loopers.infrastructure.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class ProductJpaEntity extends BaseEntity {
    @Column(nullable = false) private long brandId;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false) private long price;
    @Column(nullable = false) private int stock;
    protected ProductJpaEntity() {}

    public ProductJpaEntity(long brandId, String name, long price, int stock) {
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }
}
