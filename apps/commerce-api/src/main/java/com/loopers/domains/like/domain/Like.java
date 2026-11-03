package com.loopers.domains.like.domain;

import com.loopers.domains.product.domain.ProductId;
import java.util.Objects;

public record Like(long userId, ProductId productId) {
    public Like { Objects.requireNonNull(productId); }
}
