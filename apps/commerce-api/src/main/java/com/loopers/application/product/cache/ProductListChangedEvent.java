package com.loopers.application.product.cache;

/**
 * 고객 상품 목록에 보이는 값(상품 구성·이름·가격, 브랜드명)이 바뀌었음을 알리는 이벤트. 관리자 쓰기 유스케이스가 트랜잭션
 * 안에서 발행하고, commit 된 뒤에만 {@link ProductListCacheEvictor} 가 목록 캐시를 비운다.
 *
 * <p>목록 캐시는 영역 전체를 비우므로 무엇이 바뀌었는지는 담지 않는다.
 */
public record ProductListChangedEvent() {
}
