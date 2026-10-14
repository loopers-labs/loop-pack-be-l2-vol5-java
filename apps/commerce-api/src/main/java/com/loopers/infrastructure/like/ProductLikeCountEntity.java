package com.loopers.infrastructure.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_like_count")
class ProductLikeCountEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductLikeCountEntity() {}

    long likeCount() {
        return likeCount;
    }
}
