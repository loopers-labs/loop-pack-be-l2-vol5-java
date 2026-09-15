package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 사용자–상품 좋아요 관계 한 건 (LIK-01). 식별자만 안다.
 * 물리 삭제하는 관계라 논리 삭제를 가진 BaseEntity를 상속하지 않는다 (ADR-03).
 */
@Entity
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_likes_user_product", columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "idx_likes_product", columnList = "product_id")
)
public class LikeModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    protected LikeModel() {}

    public LikeModel(Long userId, Long productId, ZonedDateTime likedAt) {
        this.userId = userId;
        this.productId = productId;
        this.createdAt = likedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }
}
