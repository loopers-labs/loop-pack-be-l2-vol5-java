package com.loopers.infrastructure.scheduler.shopping;

import com.loopers.application.shopping.usecase.FlushLikeCountDeltaUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scheduler.like-count.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
// 누적된 좋아요 증감분을 주기적으로 반영하는 스케줄러
public class LikeCountAggregationScheduler {
    private final FlushLikeCountDeltaUseCase flushUseCase;

    // 5초마다 좋아요 증감분 반영 실행
    @Scheduled(fixedDelay = 5_000)
    public void flush() {
        try {
            flushUseCase.execute();
        } catch (RuntimeException exception) {
            log.error("상품 좋아요 증감분 반영에 실패했습니다. 다음 주기에 다시 시도합니다.", exception);
        }
    }
}
