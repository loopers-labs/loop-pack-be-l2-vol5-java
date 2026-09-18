package com.loopers.infrastructure.like;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.like.ProductLike;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "product_likes", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
public class ProductLikeJpaEntity extends BaseEntity {
    @Column(nullable = false) private long userId;
    @Column(nullable = false) private long productId;
    protected ProductLikeJpaEntity() {}

    public ProductLikeJpaEntity(ProductLike like) {
        userId = like.userId();
        productId = like.productId();
    }
}
