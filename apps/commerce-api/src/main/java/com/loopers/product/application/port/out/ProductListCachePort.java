package com.loopers.product.application.port.out;

import java.util.Optional;

/**
 * 고객 상품 목록 캐시 (W4). 원본은 DB이고, 이 캐시는 다시 계산하지 않으려고 둔 복사본이다.
 * <p>
 * 목록은 세대(generation)로 묶인다. 목록을 바꾸는 쓰기가 commit되면 세대를 올리고, 조회는 현재 세대의 키만 읽고 쓴다.
 * 그래서 변경 전에 DB를 읽은 조회가 늦게 저장해도 옛 세대의 키에 들어갈 뿐, 이후 조회에는 보이지 않는다 (늦은 채움 방지).
 */
public interface ProductListCachePort {

    /**
     * 현재 세대. 조회는 DB를 읽기 <b>전에</b> 이 값을 읽어, 저장할 때도 같은 값을 쓴다.
     */
    long currentGeneration();

    Optional<ProductListPage> find(long generation, ProductListKey key);

    void save(long generation, ProductListKey key, ProductListPage page);

    /**
     * 목록 응답을 바꾸는 쓰기가 commit됐다. 세대를 올려 저장된 목록을 모두 쓰지 않게 한다. 옛 세대의 키는 TTL로 사라진다.
     */
    void invalidateAll();
}
