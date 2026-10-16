package com.loopers.application.mall.event;

import java.util.Set;

// 커밋 후 해당 상품들의 상세 조회 복사본을 무효화한다.
public record ProductDetailsChangedEvent(Set<Long> productIds) {
    public ProductDetailsChangedEvent {
        productIds = Set.copyOf(productIds);
    }
}
