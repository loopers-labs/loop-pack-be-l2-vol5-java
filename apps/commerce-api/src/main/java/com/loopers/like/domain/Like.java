package com.loopers.like.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "product_like",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_like_user_product",
        columnNames = {"user_id", "product_id"}
    )
)
public class Like extends BaseEntity {

    private Long userId;
    private Long productId;

    protected Like() {
    }

    public Like(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void cancel(Long requesterId) {
        if (!userId.equals(requesterId)) {
            throw new CoreException(ErrorCode.LIKE_NOT_FOUND);
        }
    }
}
