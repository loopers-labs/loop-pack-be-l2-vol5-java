package com.loopers.product.application.port.out;

import java.util.Optional;

/**
 * 고객 상품 목록 캐시 (W4). 원본은 DB이고, 이 캐시는 다시 계산하지 않으려고 둔 복사본이다.
 */
public interface ProductListCachePort {

    Optional<ProductListPage> find(ProductListKey key);

    void save(ProductListKey key, ProductListPage page);

    /**
     * 목록 응답을 바꾸는 쓰기가 일어났다. 저장된 목록을 모두 믿을 수 없게 만든다.
     */
    void invalidateAll();
}
