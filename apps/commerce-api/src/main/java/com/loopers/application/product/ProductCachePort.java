package com.loopers.application.product;

public interface ProductCachePort {
    void evictByBrandId(Long brandId);
}
