package com.loopers.application.product;

import com.loopers.domain.product.ProductSort;

import java.util.Collection;
import java.util.Map;

/**
 * 상품 조회 캐시 포트 — 구현(Redis)은 infrastructure에 둔다.
 * 어떤 메서드도 예외를 던지지 않는다: 정답은 DB이고 캐시는 사본이라, 캐시 실패가 요청 실패가 되면 안 된다
 * (docs/week4/design.md 8번 섹션). 조회 실패는 CacheResult.unavailable, 저장 실패는 false, 무효화 실패는 WARN 로그로만 남긴다.
 */
public interface ProductCache {

    /** 현재 목록 버전을 읽고, 그 버전의 목록 키를 조회한다. */
    ProductListLookup lookupProductIds(Long brandId, ProductSort sort, int page, int size);

    /** lookup 때 읽은 버전의 키에 저장한다(버전을 다시 읽지 않는다). lookup의 key가 없으면 저장하지 않는다. */
    boolean putProductIds(ProductListLookup lookup, CachedProductIds value);

    /** MGET. 캐시에 없는 id는 map에서 빠진다(hit이어도 비어 있을 수 있음). */
    CacheResult<Map<Long, CachedProduct>> getProducts(Collection<Long> ids);

    boolean putProducts(Collection<CachedProduct> products);

    CacheResult<Map<Long, CachedBrand>> getBrands(Collection<Long> ids);

    boolean putBrands(Collection<CachedBrand> brands);

    void evictProducts(Collection<Long> ids);

    void evictBrand(Long id);

    /** 목록 키 전체를 읽히지 않게 한다 — 키를 찾아 지우는 대신 버전을 올리고, 이전 키는 TTL로 사라진다. */
    void bumpListVersion();
}
