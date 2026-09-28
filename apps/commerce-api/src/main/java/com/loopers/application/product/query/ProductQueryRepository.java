package com.loopers.application.product.query;

import com.loopers.domain.product.ProductSort;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Optional;

/** 상품 조회 전용 Repository. 같은 BC 안 AG 를 조인해 {@link ProductView} 로 바로 반환한다. BC 간 조인은 하지 않는다 (DR-31). */
public interface ProductQueryRepository {
    /** FR-PRODUCT-01: 삭제되지 않은 상품만, 정렬 하나 (ASM-08). */
    PageResult<ProductView.Summary> findActivePage(ProductSort sort, PageQuery query);

    /** FR-PRODUCT-02: 존재하고 삭제되지 않은 상품. */
    Optional<ProductView.Summary> findActive(Long productId);

    /** FR-ADMIN-PRODUCT-01: 삭제 포함, 최신순. */
    PageResult<ProductView.Admin> findPage(PageQuery query);

    /** FR-ADMIN-PRODUCT-03: 삭제 여부 무관. */
    Optional<ProductView.Admin> find(Long productId);
}
