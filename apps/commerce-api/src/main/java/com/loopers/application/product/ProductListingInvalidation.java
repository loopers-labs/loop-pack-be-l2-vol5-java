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

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 50;

    private final ProductListingCache productListingCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProductListingChanged event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (productListingCache.invalidate(event.brandId())) {
                return;
            }
            if (attempt < MAX_ATTEMPTS && !pause(BACKOFF_MILLIS * attempt)) {
                break;
            }
        }
        log.error("product listing cache stays stale until TTL: invalidation gave up for brand {}", event.brandId());
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
