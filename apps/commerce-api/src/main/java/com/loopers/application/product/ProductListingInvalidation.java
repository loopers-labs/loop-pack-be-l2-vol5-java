package com.loopers.application.product;

import com.loopers.domain.product.ProductListingCache;
import com.loopers.domain.product.ProductListingChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
class ProductListingInvalidation {

    private final ProductListingCache productListingCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProductListingChanged event) {
        productListingCache.invalidate(event.brandId());
    }
}
