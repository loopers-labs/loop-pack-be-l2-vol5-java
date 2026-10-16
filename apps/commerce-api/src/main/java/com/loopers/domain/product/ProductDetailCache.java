package com.loopers.domain.product;

import java.util.Collection;
import java.util.Optional;

/**
 * 고객 상세 기본정보({@link ProductDetailData.CacheValue})의 공용 캐시 포트.
 * 저장할 때 정한 고정 TTL 로만 만료되며 조회가 만료 시각을 바꾸지 않는다.
 */
public interface ProductDetailCache {

    /** false 면 DB-only 비교 모드다. 호출자는 기본정보를 캐시에서 읽거나 채우지 않는다. */
    boolean isEnabled();

    /**
     * 저장된 기본정보를 돌려준다. 없거나, 저장 형식이 올바르지 않거나, 캐시 저장소 오류가 나면 비어 있으며
     * 구현이 오류를 로그로 남긴다. 호출자는 빈 결과를 miss 로 보고 DB 에서 다시 읽는다.
     */
    Optional<ProductDetailData.CacheValue> find(Long productId);

    /** commit 된 DB 값만 고정 TTL 로 저장한다. 캐시 저장소 오류는 로그만 남기고 전파하지 않는다. */
    void put(Long productId, ProductDetailData.CacheValue cacheValue);

    /** 기본정보를 삭제한다. 캐시 저장소 오류는 호출자에게 전파한다. */
    void evictAll(Collection<Long> productIds);
}
