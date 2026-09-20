package com.loopers.domain.catalog;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    List<ProductModel> findByIds(Collection<Long> ids);

    /** FR-ADMIN-BRAND-05: 삭제되지 않은 소속 상품 유무 (재고 0 도 "있음"). */
    boolean existsActiveByBrandId(Long brandId);

    /** FR-PRODUCT-01: 삭제되지 않은 상품만, 정렬 하나 (ASM-08). */
    PageResult<ProductModel> findActivePage(ProductSort sort, PageQuery query);

    /** FR-ADMIN-PRODUCT-01: 삭제 포함, 최신순. */
    PageResult<ProductModel> findPage(PageQuery query);
}
