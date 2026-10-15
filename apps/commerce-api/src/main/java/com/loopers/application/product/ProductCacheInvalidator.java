package com.loopers.application.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * 쓰기 경로가 부르는 캐시 무효화. 모든 무효화는 commit이 성공한 뒤에 실행한다 (docs/week4/design.md 7번 섹션):
 * commit 전에 지우면 그 사이 다른 조회가 바뀌기 전 값을 다시 채우고, 롤백되면 지울 이유가 없다.
 * - 트랜잭션 안에서 불리면 afterCommit에 등록한다(롤백이면 실행되지 않는다).
 * - 트랜잭션 밖에서 불리면 즉시 실행한다 — Facade에 트랜잭션이 없고 Service에만 있는 경로는 Service가 이미 commit한 뒤다.
 * 실패해도 쓰기 요청은 성공이다(DB가 이미 정답). 실패 로그는 ProductCache 구현이 남긴다 (8번 섹션).
 */
@RequiredArgsConstructor
@Component
public class ProductCacheInvalidator {

    private final ProductCache productCache;

    public void productsChanged(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> snapshot = List.copyOf(ids);
        runAfterCommit(() -> productCache.evictProducts(snapshot));
    }

    public void productChanged(Long id) {
        productsChanged(List.of(id));
    }

    public void brandChanged(Long id) {
        runAfterCommit(() -> productCache.evictBrand(id));
    }

    public void listChanged() {
        runAfterCommit(productCache::bumpListVersion);
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
