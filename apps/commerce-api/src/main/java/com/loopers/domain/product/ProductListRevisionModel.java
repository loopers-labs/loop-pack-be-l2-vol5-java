package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// 브랜드별 상품 목록의 revision을 정렬마다 따로 두고, 한 브랜드를 여러 칸(slot)의 행으로 나눈다. 목록 캐시 키는 칸을 모두 더한 값을 쓴다.
// 정렬마다 나누는 이유: 가격 수정은 가격순만, 좋아요는 좋아요순만 순서를 바꾸므로 다른 정렬의 캐시는 그대로 쓸 수 있다.
// 칸으로 나누는 이유: 한 브랜드에 몰린 좋아요가 같은 행의 잠금을 기다리며 줄 서지 않게 한다
@Entity
@Table(name = "product_list_revision")
public class ProductListRevisionModel {

    @EmbeddedId
    private ProductListRevisionId id;

    @Column(name = "latest_revision", nullable = false)
    private long latestRevision;

    @Column(name = "price_revision", nullable = false)
    private long priceRevision;

    @Column(name = "likes_revision", nullable = false)
    private long likesRevision;

    protected ProductListRevisionModel() {
    }
}
