package com.loopers.infrastructure.scheduler.shopping;

import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scheduler.like-count.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
// 웹 서버 시작 전에 좋아요 수를 전체 재집계하는 시작 시 실행기
public class LikeCountStartupAggregator implements SmartInitializingSingleton {
    private final LikeCountAggregationUseCase aggregationUseCase;

    // 모든 싱글톤 초기화 직후 전체 재집계 1회 실행
    @Override
    public void afterSingletonsInstantiated() {
        try {
            aggregationUseCase.execute();
        } catch (RuntimeException exception) {
            log.error("시작 시 상품 좋아요 수 전체 재집계에 실패했습니다.", exception);
        }
    }
}
