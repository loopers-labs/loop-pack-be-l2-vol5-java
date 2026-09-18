package com.loopers.domain.order;

/** 주문을 만들 때의 품목 한 줄. 상품명 · 단가는 생성 시점의 스냅샷이다. */
public record OrderLine(Long productId, String productName, long unitPrice, int quantity) {}
