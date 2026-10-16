package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

/** Relationship-derived state; persisted changes use atomic SQL in LikeRepository. */
@Entity
@Table(name = "product_like_count")
@Check(name = "chk_product_like_count_nonnegative", constraints = "like_count >= 0")
public class LikeCountModel {
    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected LikeCountModel() {}

    public LikeCountModel(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }
        this.productId = productId;
    }

    public Long getProductId() { return productId; }

    public long getLikeCount() { return likeCount; }
}
