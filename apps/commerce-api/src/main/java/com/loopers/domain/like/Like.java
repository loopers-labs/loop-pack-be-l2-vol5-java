package com.loopers.domain.like;

import com.loopers.domain.product.ProductId;
import java.util.Objects;

public record Like(long userId, ProductId productId) {
    public Like { Objects.requireNonNull(productId); }
}
