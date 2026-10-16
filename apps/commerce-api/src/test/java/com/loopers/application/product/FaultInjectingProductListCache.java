package com.loopers.application.product;

import com.loopers.domain.product.CachedProductItem;
import com.loopers.domain.product.ProductListCache;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 실패·경쟁 장면을 재현하기 위해 실제 캐시 구현을 감싸는 테스트 전용 포장지.
 * 기본 모드(PASS_THROUGH)에서는 모든 호출을 실제 구현에 그대로 넘긴다.
 *
 * - FAIL_EVICT_DELETE: commit 뒤 캐시 삭제(DEL)만 예외가 나는 구현으로 무효화를 넘긴다. 운영 코드의 catch·로그 경로가 그대로 실행된다.
 * - HOLD_SAVE: 첫 번째 save 에 도착하면 신호를 보내고, 테스트가 열어 줄 때까지(유한 대기) 저장을 미룬다. 늦은 캐시 채움 재현용.
 */
class FaultInjectingProductListCache implements ProductListCache {

    enum Mode { PASS_THROUGH, FAIL_EVICT_DELETE, HOLD_SAVE }

    private static final long HOLD_TIMEOUT_SECONDS = 5;

    private final ProductListCache delegate;
    private final ProductListCache failingDeleteDelegate;

    private volatile Mode mode = Mode.PASS_THROUGH;
    private volatile CountDownLatch saveReached = new CountDownLatch(1);
    private volatile CountDownLatch saveGate = new CountDownLatch(1);

    FaultInjectingProductListCache(ProductListCache delegate, ProductListCache failingDeleteDelegate) {
        this.delegate = delegate;
        this.failingDeleteDelegate = failingDeleteDelegate;
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * 테스트 순서에 따라 결과가 바뀌지 않도록 매 테스트 뒤에 통과 모드로 되돌리고 래치를 새로 만든다.
     * 기다리는 스레드가 있으면 열어서 종료시킨다.
     */
    void reset() {
        mode = Mode.PASS_THROUGH;
        saveGate.countDown();
        saveReached = new CountDownLatch(1);
        saveGate = new CountDownLatch(1);
    }

    /** 느린 조회가 DB 를 다 읽고 save 직전까지 왔는지 기다린다. */
    boolean awaitSaveReached() throws InterruptedException {
        return saveReached.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** 미뤄 둔 save 를 진행시킨다. */
    void releaseSave() {
        saveGate.countDown();
    }

    @Override
    public Optional<List<CachedProductItem>> find(Long brandId, int page) {
        return delegate.find(brandId, page);
    }

    /**
     * HOLD_SAVE 는 첫 번째 save 한 번에만 걸린다. 잡는 즉시 통과 모드로 바꿔 뒤따르는 save(만료 뒤 재조회 등)는 멈추지 않는다.
     */
    @Override
    public void save(Long brandId, int page, List<CachedProductItem> items) {
        if (mode == Mode.HOLD_SAVE) {
            mode = Mode.PASS_THROUGH;
            saveReached.countDown();
            try {
                if (!saveGate.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("save 대기가 " + HOLD_TIMEOUT_SECONDS + "초 안에 풀리지 않았다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("save 대기 중 인터럽트", e);
            }
        }
        delegate.save(brandId, page, items);
    }

    @Override
    public void evictBrandAfterCommit(Long brandId) {
        if (mode == Mode.FAIL_EVICT_DELETE) {
            failingDeleteDelegate.evictBrandAfterCommit(brandId);
            return;
        }
        delegate.evictBrandAfterCommit(brandId);
    }
}
