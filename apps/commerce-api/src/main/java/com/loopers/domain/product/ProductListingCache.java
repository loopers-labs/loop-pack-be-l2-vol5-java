package com.loopers.domain.product;

import com.loopers.domain.common.PageWindow;

import java.util.Optional;

public interface ProductListingCache {

    Optional<PageWindow<ProductViewQuery.Listing>> find(Long brandId, ProductViewQuery.Sort sort);

    void save(Long brandId, ProductViewQuery.Sort sort, PageWindow<ProductViewQuery.Listing> page);

    boolean invalidate(Long brandId);
}
