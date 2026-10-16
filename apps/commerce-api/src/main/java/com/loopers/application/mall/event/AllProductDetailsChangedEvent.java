package com.loopers.application.mall.event;

// 전체 좋아요 재집계가 커밋되면 상품 상세 조회 복사본 전체를 무효화한다.
public record AllProductDetailsChangedEvent() {}
