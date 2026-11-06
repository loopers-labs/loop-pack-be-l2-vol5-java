package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 상품별 revision. 상품 캐시에 담는 값(이름·가격·재고·좋아요 수)을 바꾸는 쓰기가 같은 트랜잭션에서 1씩 올리고, 상품 캐시 키가 이 값을 쓴다
@Entity
@Table(name = "product_revision")
public class ProductRevisionModel {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected ProductRevisionModel() {
    }

    public Long getProductId() {
        return productId;
    }

    public long getRevision() {
        return revision;
    }
}
