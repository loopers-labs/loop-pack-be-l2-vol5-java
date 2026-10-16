package com.loopers.domain.product;

import java.util.Optional;

public interface ProductListCache {
    boolean isEnabled();

    Optional<ProductListSnapshot> find(long revision);

    void store(ProductListSnapshot snapshot);
}
