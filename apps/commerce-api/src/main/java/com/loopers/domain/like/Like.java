package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(name = "uk_likes_user_product", columnNames = {"user_id", "product_id"})
)
public class Like extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    protected Like() {}

    public Like(Long userId, Long productId) {
        if (userId == null) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "사용자는 필수입니다.");
        }
        if (productId == null) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "상품은 필수입니다.");
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
