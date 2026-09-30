package com.loopers.infrastructure.scheduler.shopping;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.loopers.application.shopping.usecase.FlushLikeCountDeltaUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikeCountAggregationSchedulerTest {
    @DisplayName("증감분 반영 유스케이스에 실행을 위임한다")
    @Test
    void delegatesFlush() {
        FlushLikeCountDeltaUseCase useCase = mock(FlushLikeCountDeltaUseCase.class);
        LikeCountAggregationScheduler scheduler = new LikeCountAggregationScheduler(useCase);

        scheduler.flush();

        verify(useCase).execute();
    }

    @DisplayName("반영 실패를 전파하지 않아 다음 주기 실행을 유지한다")
    @Test
    void keepsSchedule_whenFlushFails() {
        FlushLikeCountDeltaUseCase useCase = mock(FlushLikeCountDeltaUseCase.class);
        doThrow(new IllegalStateException("실패")).when(useCase).execute();
        LikeCountAggregationScheduler scheduler = new LikeCountAggregationScheduler(useCase);

        scheduler.flush();

        verify(useCase).execute();
    }
}
