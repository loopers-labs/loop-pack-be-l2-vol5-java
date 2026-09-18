package com.loopers.infrastructure.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "product",
    indexes = @Index(name = "idx_product_brand_id", columnList = "brand_id, deleted_at")
)
class ProductEntity extends BaseEntity {

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected ProductEntity() {}

    static ProductEntity from(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.brandId = product.getBrandId();
        entity.name = product.getName();
        entity.price = product.getPrice().value();
        entity.quantity = product.getQuantity().value();
        return entity;
    }

    void apply(Product product) {
        this.name = product.getName();
        this.price = product.getPrice().value();
        this.quantity = product.getQuantity().value();
        if (product.isDeleted()) {
            delete();
        }
    }

    Product toDomain() {
        return Product.restore(
            getId(), brandId, name, Price.of(price), Quantity.of(quantity), getDeletedAt() != null);
    }
}
