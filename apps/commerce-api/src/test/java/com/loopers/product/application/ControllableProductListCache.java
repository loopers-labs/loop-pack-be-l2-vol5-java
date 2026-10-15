package com.loopers.product.application;

import com.loopers.product.adapter.out.cache.RedisProductListCacheAdapter;
import com.loopers.product.application.port.out.ProductListCachePort;
import com.loopers.product.application.port.out.ProductListKey;
import com.loopers.product.application.port.out.ProductListPage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ControllableProductListCache implements ProductListCachePort {

    private final ProductListCachePort delegate;

    private volatile CountDownLatch invalidateReached;
    private volatile CountDownLatch invalidateRelease;
    private volatile CountDownLatch saveReached;
    private volatile CountDownLatch saveRelease;
    private volatile boolean failInvalidate;

    public ControllableProductListCache(ProductListCachePort delegate) {
        this.delegate = delegate;
    }

    // 다음 무효화가 실제 무효화를 마친 직후 멈춘다.
    public void pauseAfterNextInvalidate() {
        invalidateReached = new CountDownLatch(1);
        invalidateRelease = new CountDownLatch(1);
    }

    public boolean awaitInvalidatePaused(Duration timeout) throws InterruptedException {
        return invalidateReached.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releaseInvalidate() {
        invalidateRelease.countDown();
    }

    /** 다음 저장이 Redis에 쓰기 직전에 멈춘다 — DB에서 이미 읽은 결과를 들고 있는 상태. */
    public void pauseBeforeNextSave() {
        saveReached = new CountDownLatch(1);
        saveRelease = new CountDownLatch(1);
    }

    public boolean awaitSavePaused(Duration timeout) throws InterruptedException {
        return saveReached.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releaseSave() {
        saveRelease.countDown();
    }

    /** 무효화가 Redis 오류로 실패한다. */
    public void failInvalidate(boolean fail) {
        this.failInvalidate = fail;
    }

    public void reset() {
        failInvalidate = false;
        release(invalidateRelease);
        release(saveRelease);
        invalidateReached = null;
        invalidateRelease = null;
        saveReached = null;
        saveRelease = null;
    }

    private void release(CountDownLatch latch) {
        if (latch != null) {
            latch.countDown();
        }
    }

    @Override
    public Optional<ProductListPage> find(ProductListKey key) {
        return delegate.find(key);
    }

    @Override
    public void save(ProductListKey key, ProductListPage page) {
        CountDownLatch reached = saveReached;
        CountDownLatch release = saveRelease;
        if (reached != null && reached.getCount() > 0) {
            reached.countDown();
            await(release);
        }
        delegate.save(key, page);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 장벽이 10초 안에 풀리지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void invalidateAll() {
        if (failInvalidate) {
            throw new RedisConnectionFailureException("테스트: 목록 캐시 무효화 실패");
        }
        delegate.invalidateAll();
        CountDownLatch reached = invalidateReached;
        CountDownLatch release = invalidateRelease;
        if (reached != null && reached.getCount() > 0){
            reached.countDown();
            await(release);
        }
    }

    @TestConfiguration
    public static class Config {

        @Primary
        @Bean
        ControllableProductListCache controllableProductListCache(RedisProductListCacheAdapter adapter) {
            return new ControllableProductListCache(adapter);
        }
    }
}
