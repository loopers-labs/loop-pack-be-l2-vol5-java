package com.loopers.application.product;

import java.util.List;
import java.util.Optional;

public interface ProductListCache {

    // 브랜드 필터가 없으면 전체 목록의 버전을 반환한다.
    long currentVersion(Long brandId);

    Optional<List<CustomerProductInfo>> find(ProductListCacheKey key);

    void put(ProductListCacheKey key, List<CustomerProductInfo> products);

    // 해당 브랜드 목록과 전체 목록의 버전을 올린다.
    void invalidate(Long brandId);
}
