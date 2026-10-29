package com.loopers.application.product.port;

import com.loopers.application.product.ProductListItem;
import com.loopers.application.product.ProductListQuery;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

// 고객 상품 목록 cache-aside 저장소. 구현은 캐시를 쓸 수 없을 때 예외 대신 빈 값·무시로 응답해 DB 조회로 이어지게 한다.
public interface ProductListCache {
    // 현재 목록 버전. 캐시를 쓸 수 없으면 empty
    OptionalLong currentVersion();

    Optional<List<ProductListItem>> find(long version, ProductListQuery query);

    void save(long version, ProductListQuery query, List<ProductListItem> items);

    // 모든 목록 키를 한 번에 무효화한다(버전 증가).
    void invalidate();
}
