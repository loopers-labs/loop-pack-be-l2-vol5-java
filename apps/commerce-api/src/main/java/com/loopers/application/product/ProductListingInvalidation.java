package com.loopers.application.product;

import com.loopers.domain.product.ProductListingCache;
import com.loopers.domain.product.ProductListingChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
class ProductListingInvalidation {

    private final ProductListingCache productListingCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProductListingChanged event) {
        if (!productListingCache.invalidate(event.brandId())) {
            log.error("product listing cache stays stale until TTL: invalidation failed for brand {}", event.brandId());
        }
    }
}
