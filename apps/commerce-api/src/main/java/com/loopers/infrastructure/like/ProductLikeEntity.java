package com.loopers.infrastructure.like;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.like.ProductLike;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "product_like",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_like_user_id_product_id", columnNames = {"user_id", "product_id"}
    ),
    indexes = @Index(name = "idx_product_like_product_id", columnList = "product_id")
)
class ProductLikeEntity extends AuditEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    protected ProductLikeEntity() {}

    static ProductLikeEntity from(ProductLike like) {
        ProductLikeEntity entity = new ProductLikeEntity();
        entity.userId = like.userId();
        entity.productId = like.productId();
        return entity;
    }
}
