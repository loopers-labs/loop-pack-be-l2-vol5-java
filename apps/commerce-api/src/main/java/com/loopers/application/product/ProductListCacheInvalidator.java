package com.loopers.application.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListCacheInvalidator {

    private final ProductListCache productListCache;

    // rollback된 변경으로 캐시를 비우지 않도록 커밋된 뒤에만 버전을 올린다.
    public void invalidateAfterCommit(Long brandId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(brandId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(brandId);
            }
        });
    }

    // 이미 커밋된 변경의 응답을 실패로 바꾸지 않는다. 남은 이전 항목은 TTL로 만료되고 삭제 상품은 조회 시 DB에서 걸러진다.
    private void invalidate(Long brandId) {
        try {
            productListCache.invalidate(brandId);
        } catch (RuntimeException exception) {
            log.warn("상품 목록 캐시를 무효화하지 못했습니다. brandId={}, cause={}", brandId, exception.toString());
        }
    }
}
