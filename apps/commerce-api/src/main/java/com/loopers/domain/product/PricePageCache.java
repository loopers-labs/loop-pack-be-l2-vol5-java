package com.loopers.domain.product;

import java.util.Optional;

public interface PricePageCache {
    Optional<PricePage> get(Long brandId, int page, int size);
    void put(Long brandId, int page, int size, PricePage value);
    void evict(Long brandId, int page, int size);
}
