package com.loopers.application.product;

import java.util.List;
import java.util.Map;

// 상품 캐시는 상품 id와 그 상품의 revision으로 찾는다. 오류는 호출자에게 던지지 않는다 — 찾지 못한 상품은 결과에서 빠지고, 저장 실패는 무시한다
public interface ProductCache {
    Map<Long, ProductSnapshot> findAll(Map<Long, Long> revisionsByProductId);

    void saveAll(Map<Long, Long> revisionsByProductId, List<ProductSnapshot> snapshots);
}
