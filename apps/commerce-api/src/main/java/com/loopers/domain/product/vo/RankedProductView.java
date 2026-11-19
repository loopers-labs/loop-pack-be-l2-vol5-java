package com.loopers.domain.product.vo;

/** 공개된 랭킹의 한 항목. 점수 · 순위는 계산 당시 값, 이름 · 가격 · 브랜드는 조회 시점의 현재 값이다 (9주차 D-188) */
public record RankedProductView(
    Long productId,
    long score,
    int ranking,
    String name,
    long price,
    Long brandId,
    String brandName
) {}
