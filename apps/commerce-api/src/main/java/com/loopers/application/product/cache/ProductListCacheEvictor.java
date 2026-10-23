package com.loopers.application.product.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 관리자 쓰기가 commit 된 뒤 상품 목록 캐시를 비운다.
 *
 * <p>트랜잭션 안에서 바로 지우면 commit 전에 다른 조회가 옛 값을 다시 채울 수 있어 commit 뒤에 지운다. rollback 이면
 * 호출되지 않아 캐시를 건드리지 않는다. 이 시점에 DB 는 이미 commit 되었으므로 비우기가 실패해도 관리자 요청을 실패로
 * 바꾸지 않는다. 남은 옛 값은 TTL 이 정리한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListCacheEvictor {

    private final ProductListCache productListCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evict(ProductListChangedEvent event) {
        try {
            productListCache.evictAll();
        } catch (RuntimeException e) {
            // 캐시 구현은 Redis 오류를 스스로 삼키지만, 예상 밖의 오류도 commit 된 요청을 실패로 바꾸지 않게 막는다.
            log.error("상품 목록 캐시 비우기 실패. TTL 만료까지 옛 목록이 남을 수 있습니다.", e);
        }
    }
}
