package com.loopers.product.adapter.in.event;

import com.loopers.brand.domain.BrandChangedEvent;
import com.loopers.product.application.ProductListCacheInvalidator;
import com.loopers.product.domain.ProductChangedEvent;
import com.loopers.product.domain.ProductLikesChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 목록 응답을 바꾸는 사실(상품·브랜드·좋아요 변경)을 듣고 목록 캐시를 무효화한다 (W4, ADR-W4-02).
 * <ul>
 *   <li>AFTER_COMMIT: commit이 끝난 뒤에만 실행한다. rollback되면 실행되지 않는다. commit 전에 지우면 그 사이 조회가 변경 전 값을 다시 채운다.</li>
 *   <li>fallbackExecution: 트랜잭션 밖에서 발행되면 기본값(false)은 조용히 무시한다. 무효화를 빠뜨리지 않도록 즉시 실행한다.</li>
 * </ul>
 * 발행하는 쪽은 이 리스너를 모른다. 연결은 쓰기 경로마다 "쓰기 → 목록 반영" 통합 테스트가 지킨다 (ProductListCacheIntegrationTest).
 */
@RequiredArgsConstructor
@Component
public class ProductListCacheEventListener {

    private final ProductListCacheInvalidator productListCacheInvalidator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ProductChangedEvent event) {
        productListCacheInvalidator.invalidate();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(BrandChangedEvent event) {
        productListCacheInvalidator.invalidate();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ProductLikesChangedEvent event) {
        productListCacheInvalidator.invalidate();
    }
}
