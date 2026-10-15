package com.loopers.application.product;

// 고객 상품 목록에 보이는 값(노출 상품·이름·가격·브랜드 이름)이 바뀌었음을 알린다. commit 후 목록 캐시를 무효화한다.
public record ProductListChangedEvent() { }
