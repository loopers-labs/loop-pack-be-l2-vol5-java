package com.loopers.domain.like;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 사용자–상품 관계. 사용자와 상품은 식별자로만 보관한다 (설계 2.3).
 * 같은 관계의 중복은 유니크 키가 막고(LIK-02), 좋아요 시각은 생성 시각이다.
 * MySQL 예약어와 겹치지 않도록 테이블 이름은 likes 로 둔다.
 */
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
