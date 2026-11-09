package com.loopers.domain.product;

import java.util.List;
import java.util.Map;

public interface ProductRevisionRepository {
    // 행이 없으면 1로 만들고, 있으면 1 올린다
    void increase(Long productId);

    // revision 행이 있는 상품만 담는다
    Map<Long, Long> findRevisions(List<Long> productIds);
}
