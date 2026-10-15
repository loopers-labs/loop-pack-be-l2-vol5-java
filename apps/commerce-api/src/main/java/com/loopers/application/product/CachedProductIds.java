package com.loopers.application.product;

import java.util.List;

/**
 * 목록 키에 저장하는 값 — 한 페이지의 상품 id 순서와 전체 건수.
 * id와 totalElements를 한 키에 함께 둬야 한 응답 안에서 목록과 건수가 같은 시점 값이 된다 (docs/week4/design.md 6번 섹션).
 * page·size·totalPages는 키와 totalElements에서 다시 계산할 수 있어 저장하지 않는다.
 */
public record CachedProductIds(List<Long> ids, long totalElements) {
}
