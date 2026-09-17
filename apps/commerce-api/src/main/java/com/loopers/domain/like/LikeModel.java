package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * User 와 Product 사이의 좋아요 관계. 같은 사용자–상품 관계는 중복될 수 없다.
 */
@Entity
@Table(
    name = "product_like",
    uniqueConstraints = @UniqueConstraint(name = "uk_like_user_product", columnNames = {"user_id", "product_id"})
)
@Getter
public class LikeModel extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    protected LikeModel() {}

    private LikeModel(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public static LikeModel of(Long userId, Long productId) {
        return new LikeModel(userId, productId);
    }
}
