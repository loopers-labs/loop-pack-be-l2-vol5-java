package com.loopers.domain.product;

import com.loopers.domain.common.PageWindow;

import java.util.Optional;
import java.util.OptionalLong;

public interface ProductListingCache {

    OptionalLong generation(Long brandId);

    Optional<PageWindow<ProductViewQuery.Listing>> find(Long brandId, ProductViewQuery.Sort sort, long generation);

    void save(Long brandId, ProductViewQuery.Sort sort, long generation, PageWindow<ProductViewQuery.Listing> page);
}
