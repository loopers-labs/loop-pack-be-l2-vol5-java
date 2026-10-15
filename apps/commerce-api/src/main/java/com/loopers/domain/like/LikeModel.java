package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// UK(user_id, product_id)는 "같은 사용자가 같은 상품에 좋아요 하나"라는 규칙이자 user_id로 시작하는 조회(내 좋아요 목록)의 탐색 경로다.
// 좋아요 수 집계·likes_desc는 product_id로 시작해야 하는데 UK에선 두 번째 열이라 인덱스 전체를 훑었다 —
// 규칙(UK)은 건드리지 않고 product_id 인덱스를 따로 둔다 (docs/week4/design.md 5번 섹션).
@Entity
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "idx_likes_product_id", columnList = "product_id")
)
public class LikeModel extends BaseEntity {

    private Long userId;
    private Long productId;

    protected LikeModel() {}

    public LikeModel(Long userId, Long productId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 비어있을 수 없습니다.");
        }
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "productId는 비어있을 수 없습니다.");
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
