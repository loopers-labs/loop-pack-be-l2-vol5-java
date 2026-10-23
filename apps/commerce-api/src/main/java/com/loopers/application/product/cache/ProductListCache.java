package com.loopers.application.product.cache;

import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.application.product.cache.CachedProductListInfo;

import java.util.Optional;

/**
 * 상품 목록 캐시 포트. 이를 쓰는 application 이 소유하고 infrastructure 가 구현한다.
 *
 * <p>캐시는 원본이 아니므로 구현은 저장소 오류를 호출자에게 던지지 않는다. 조회 오류는 "없음"으로, 저장·비우기 오류는
 * 무시로 처리하고 로그·메트릭만 남긴다. 호출자는 오류와 정상 miss 를 구분하지 않고 원본(DB)을 읽으면 된다.
 */
public interface ProductListCache {

    Optional<CachedProductListInfo> find(ProductListQuery query);

    void save(ProductListQuery query, CachedProductListInfo snapshot);

    /** 상품 목록 캐시 전체를 지운다. 다른 캐시 영역은 건드리지 않는다. */
    void evictAll();
}
