package com.loopers.domain.product;

import com.loopers.domain.common.Money;

public record ProductSnapshot(Long productId, String name, Money price) {}
