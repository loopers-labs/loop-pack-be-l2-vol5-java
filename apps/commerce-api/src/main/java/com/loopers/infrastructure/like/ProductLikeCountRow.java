package com.loopers.infrastructure.like;

/** 상품별 좋아요 수 조회 결과 한 행. JPQL 생성자 표현식으로 만들며 인프라 안에서만 쓴다. */
public record ProductLikeCountRow(Long productId, long likeCount) {}
