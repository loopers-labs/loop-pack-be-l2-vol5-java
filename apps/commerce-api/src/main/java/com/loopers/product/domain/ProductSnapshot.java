package com.loopers.product.domain;

import com.loopers.common.domain.Money;

public record ProductSnapshot(Long productId, String name, Money price) {}
