package com.loopers.infrastructure.initializer.mall;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("benchmark-seed")
@ConditionalOnProperty(prefix = "benchmark.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
// 명시적으로 요청한 실험 seed만 적재한다. 종료 책임은 CLI 진입점에 둔다.
public class ProductSeedRunner implements ApplicationRunner {
    private final JdbcProductSeedLoader loader;

    @Override
    public void run(ApplicationArguments args) {
        ProductSeedResult result = loader.load();
        log.info("상품 조회 실험 seed 적재·검증 완료: {}", result);
    }
}
