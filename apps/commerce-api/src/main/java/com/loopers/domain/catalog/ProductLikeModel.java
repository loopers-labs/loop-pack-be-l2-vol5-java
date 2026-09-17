package com.loopers.domain.catalog;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-04 좋아요 (TB-04 product_like, DR-10). 사용자–상품 관계, 같은 쌍에 최대 하나 (INV-04, IX-01 고유).
 * 취소는 물리 삭제 (DR-17). 상품이 DELETED 여도 관계는 유지된다 (ASM-07).
 */
@Entity
@Table(name = "product_like",
    uniqueConstraints = @UniqueConstraint(name = "ux_product_like_user_product", columnNames = {"user_id", "product_id"}),
    indexes = @Index(name = "ix_product_like_product", columnList = "product_id"))
public class ProductLikeModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    protected ProductLikeModel() {}

    public ProductLikeModel(Long userId, Long productId) {
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
