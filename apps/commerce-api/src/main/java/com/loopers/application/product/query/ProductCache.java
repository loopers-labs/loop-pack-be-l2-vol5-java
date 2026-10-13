package com.loopers.application.product.query;

import java.util.Optional;

/**
 * 고객 상품 조회 캐시 (DR-37). 키는 DB 의 revision 을 포함하므로 한 번 쓴 키의 내용은 바뀌지 않고, 쓰기 쪽은 캐시를 지우지 않는다.
 * 옛 revision 의 키는 TTL 로 정리된다. 구현은 저장소 장애를 던지지 않는다 — 읽기는 miss 로, 쓰기는 무시로 돌린다.
 */
public interface ProductCache {
    /** FR-PRODUCT-02: 상품·브랜드 revision 에 대응하는 상세. */
    Optional<ProductCacheValue.Item> findDetail(Long productId, long productRevision, long brandRevision);

    void putDetail(Long productId, long productRevision, long brandRevision, ProductCacheValue.Item item);
}
