package com.loopers.application.product;

import java.util.Optional;

// 캐시 오류는 호출자에게 던지지 않는다 — 조회는 miss와 같이 비어 있고, 저장 실패는 무시한다. 오류 횟수는 구현체가 따로 남긴다
public interface ProductListCache {
    Optional<ProductIdPage> find(ProductListCacheKey key);

    void save(ProductListCacheKey key, ProductIdPage page);
}
