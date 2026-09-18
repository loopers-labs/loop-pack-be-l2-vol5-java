package com.loopers.domain.product;

import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface ProductQueryRepository {

    Optional<ProductSummary> findDetail(long productId, boolean includeDeleted);

    PageResult<ProductSummary> findPage(Long brandId, int page, int size, ProductSort sort, boolean includeDeleted);

    PageResult<ProductSummary> findLikedPage(long userId, int page, int size);
}
