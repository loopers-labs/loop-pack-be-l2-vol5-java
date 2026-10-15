package com.loopers.application.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// ApplicationRunner가 끝난 뒤 readiness가 트래픽 수신 상태로 바뀌므로, 워밍업을 마친 뒤 요청을 받는다.
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "product.list-cache.warm-up.enabled", havingValue = "true")
public class ProductListCacheWarmUpRunner implements ApplicationRunner {

    private final ProductListCacheWarmer productListCacheWarmer;

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.nanoTime();
        ProductListCacheWarmer.Result result = productListCacheWarmer.warmUp();
        log.info("상품 목록 캐시 워밍업을 마쳤습니다. warmed={}, failed={}, elapsedMs={}",
            result.warmed(), result.failed(), (System.nanoTime() - startedAt) / 1_000_000);
    }
}
