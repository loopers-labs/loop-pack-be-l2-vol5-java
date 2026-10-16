package com.loopers.infrastructure.query.mall;

import com.loopers.application.mall.event.AllProductDetailsChangedEvent;
import com.loopers.application.mall.event.BrandProductsChangedEvent;
import com.loopers.application.mall.event.ProductDetailsChangedEvent;
import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ProductDetailCacheInvalidationListener {
    private final ProductDetailCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void on(ProductDetailsChangedEvent event) {
        cache.invalidateProducts(event.productIds());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void on(BrandProductsChangedEvent event) {
        cache.invalidateBrand(event.brandId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void on(AllProductDetailsChangedEvent event) {
        cache.invalidateAll();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void on(ProductLikeChangedEvent event) {
        cache.invalidateProducts(Set.of(event.productId()));
    }
}
