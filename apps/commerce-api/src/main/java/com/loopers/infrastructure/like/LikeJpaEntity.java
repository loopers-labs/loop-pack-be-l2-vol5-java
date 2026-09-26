package com.loopers.infrastructure.like;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "likes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_like_user_product",
        columnNames = {"user_id", "product_id"}
    )
)
public class LikeJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    private LikeJpaEntity(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

    public static LikeJpaEntity create(Long userId, Long productId) {
        return new LikeJpaEntity(userId, productId);
    }
}
