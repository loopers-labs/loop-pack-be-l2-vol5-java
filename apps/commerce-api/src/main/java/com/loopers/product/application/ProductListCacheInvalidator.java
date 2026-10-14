package com.loopers.product.application;

import com.loopers.product.application.port.out.ProductListCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 목록 응답을 바꾸는 쓰기(상품·브랜드·좋아요)가 부른다 (W4).
 * <ul>
 *   <li>무효화는 commit이 끝난 뒤에 한다. commit 전에 지우면, 그 사이 조회가 아직 commit 안 된 변경 전 값을 다시 채운다.</li>
 *   <li>rollback되면 아무것도 하지 않는다. 바뀌지 않은 원본에 맞는 캐시를 지울 이유가 없다.</li>
 *   <li>무효화가 실패해도 이미 commit된 변경을 실패로 알리지 않는다. 대신 그 목록은 TTL이 지날 때까지 옛 값일 수 있다.</li>
 * </ul>
 * {@code @Transactional} 하나가 MySQL과 Redis를 원자적으로 묶지는 못한다 — 그래서 실패 창을 TTL로 제한한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListCacheInvalidator {

    private final ProductListCachePort productListCachePort;

    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateQuietly();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateQuietly();
            }
        });
    }

    private void invalidateQuietly() {
        try {
            productListCachePort.invalidateAll();
        } catch (RuntimeException e) {
            // afterCommit에서 던지면 commit된 요청이 500으로 응답한다. 여기서 멈추고 기록만 한다.
            log.warn("상품 목록 캐시 무효화 실패 — TTL이 지날 때까지 옛 목록이 보일 수 있습니다.", e);
        }
    }
}
