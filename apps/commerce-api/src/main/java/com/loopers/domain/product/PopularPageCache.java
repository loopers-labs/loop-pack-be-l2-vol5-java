package com.loopers.domain.product;

import java.util.Optional;

public interface PopularPageCache {
    Optional<PopularPage> get(Long brandId);

    void put(Long brandId, PopularPage page);

    void evict(Long brandId);
}
