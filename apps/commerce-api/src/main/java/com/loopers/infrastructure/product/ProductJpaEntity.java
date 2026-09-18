package com.loopers.infrastructure.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.product.Product;
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

    public ProductJpaEntity(Product product) {
        brandId = product.getBrandId();
        update(product);
    }
    public void update(Product product) {
        name = product.getName();
        price = product.getPrice();
        stock = product.getStock();
        if (product.isDeleted()) { delete(); }
    }
    public Product toDomain() {
        return Product.restore(getId(), brandId, name, price, stock, getDeletedAt() != null);
    }
}
