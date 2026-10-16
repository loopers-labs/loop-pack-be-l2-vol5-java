package com.loopers.domain.product;

import java.util.List;
import java.util.Optional;

/**
 * 브랜드 필터 + 가격순 상품 목록의 페이지 단위 캐시.
 * 캐시 저장소 오류는 호출자에게 전파하지 않는다. find 는 miss 로, save 는 아무 일도 하지 않은 것으로 끝난다.
 */
public interface ProductListCache {

    Optional<List<CachedProductItem>> find(Long brandId, int page);

    void save(Long brandId, int page, List<CachedProductItem> items);

    /**
     * 현재 트랜잭션이 commit 된 뒤 해당 브랜드의 캐시 페이지를 지우도록 등록한다.
     * 트랜잭션 밖에서 호출하면 예외가 난다. 쓰기 경로의 트랜잭션 안에서만 호출한다.
     */
    void evictBrandAfterCommit(Long brandId);
}
