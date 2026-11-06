package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// 상품별 좋아요 수는 (product_id, deleted_at) 인덱스만으로 센다 — 유니크 키는 user_id가 앞이라 상품으로 찾지 못한다
@Entity
@Table(
    name = "product_like",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "idx_product_like_product_deleted", columnList = "product_id, deleted_at")
)
public class LikeModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    protected LikeModel() {
    }

    public LikeModel(Long userId, Long productId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자는 비어있을 수 없습니다.");
        }
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품은 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.productId = productId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }
}
