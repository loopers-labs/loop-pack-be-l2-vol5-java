package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 상품별 좋아요 수. 목록 조회마다 관계를 집계하지 않도록 따로 둔 값이다 (4주차 설계 docs/week4/like-count-design.md).
 * 재고 · 가격을 잠그는 product 행과 잠금이 겹치지 않도록 별도 테이블에 둔다.
 * 상품을 만들 때 0 으로 함께 만들고, 증감은 관계가 실제로 생기거나 지워졌을 때 원자적 갱신으로만 한다.
 */
@Entity
@Table(
    name = "product_like_count",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_like_count_product", columnNames = "product_id"),
    indexes = @Index(name = "idx_product_like_count_count", columnList = "like_count, product_id")
)
public class ProductLikeCount extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductLikeCount() {}

    public ProductLikeCount(Long productId) {
        this.productId = productId;
        this.likeCount = 0;
    }

    public Long getProductId() {
        return productId;
    }

    public long getLikeCount() {
        return likeCount;
    }
}
