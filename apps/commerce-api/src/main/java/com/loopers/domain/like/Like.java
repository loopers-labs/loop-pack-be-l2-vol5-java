package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 사용자와 상품의 좋아요 관계. 동일 조합은 하나만 존재한다.
 */
@Getter
@EqualsAndHashCode(of = {"userId", "productId"})
@Entity
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_likes_user_product", columnNames = {"user_id", "product_id"})
)
public class Like extends BaseEntity {

    private Long userId;

    private Long productId;

    protected Like() {}

    public Like(Long userId, Long productId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "좋아요의 사용자 식별자는 필수입니다.");
        }
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "좋아요의 상품 식별자는 필수입니다.");
        }
        this.userId = userId;
        this.productId = productId;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
