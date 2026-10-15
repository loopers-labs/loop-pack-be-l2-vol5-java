package com.loopers.application.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RequiredArgsConstructor
@Component
public class ProductListCacheInvalidator {

    private final ProductListCache productListCache;

    // rollback된 변경으로 캐시를 비우지 않도록 커밋된 뒤에만 버전을 올린다.
    public void invalidateAfterCommit(Long brandId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            productListCache.invalidate(brandId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productListCache.invalidate(brandId);
            }
        });
    }
}
