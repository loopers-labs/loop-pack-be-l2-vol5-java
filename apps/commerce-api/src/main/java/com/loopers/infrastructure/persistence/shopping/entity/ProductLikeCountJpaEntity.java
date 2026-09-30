package com.loopers.infrastructure.persistence.shopping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_like_counts")
// 상품 좋아요 집계 JPA 엔티티
public class ProductLikeCountJpaEntity {
    @Id
    @Column(name = "product_id")
    private Long productId;
    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductLikeCountJpaEntity() {}

    public ProductLikeCountJpaEntity(long productId, long likeCount) {
        if (productId <= 0 || likeCount < 0) {
            throw new IllegalArgumentException("좋아요 집계 값이 올바르지 않습니다.");
        }
        this.productId = productId;
        this.likeCount = likeCount;
    }

    public Long getProductId() { return productId; }
    public long getLikeCount() { return likeCount; }
}
