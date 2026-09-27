package com.loopers.application.product.query;

import com.loopers.domain.product.ProductSort;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

/** 상품 조회 전용 Repository. 같은 BC 안 AG 를 조인해 {@link ProductView} 로 바로 반환한다. BC 간 조인은 하지 않는다 (DR-31). */
public interface ProductQueryRepository {
    /** FR-PRODUCT-01: 삭제되지 않은 상품만, 정렬 하나 (ASM-08). */
    PageResult<ProductView.Summary> findActivePage(ProductSort sort, PageQuery query);
}
