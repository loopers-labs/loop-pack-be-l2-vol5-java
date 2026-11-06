package com.loopers.application.product;

import java.util.List;

// 목록 캐시에 담는 값. 상품 정보는 담지 않고 정렬된 상품 id와 전체 건수만 담는다 — 가격·재고·좋아요 수가 바뀌어도 순서가 그대로면 다시 쓸 수 있다
public record ProductIdPage(List<Long> productIds, long totalElements) {
}
