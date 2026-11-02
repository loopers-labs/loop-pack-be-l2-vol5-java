package com.loopers.like.domain;

import com.loopers.product.domain.ProductId;
import java.util.Objects;

public record Like(long userId, ProductId productId) {
    public Like { Objects.requireNonNull(productId); }
}
