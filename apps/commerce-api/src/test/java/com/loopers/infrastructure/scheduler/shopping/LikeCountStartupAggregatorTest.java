package com.loopers.infrastructure.scheduler.shopping;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikeCountStartupAggregatorTest {
    @DisplayName("초기화 시 전체 재집계를 1회 호출한다")
    @Test
    void aggregatesOnce_afterSingletonsInstantiated() {
        LikeCountAggregationUseCase useCase = mock(LikeCountAggregationUseCase.class);
        LikeCountStartupAggregator aggregator = new LikeCountStartupAggregator(useCase);

        aggregator.afterSingletonsInstantiated();

        verify(useCase).execute();
    }

    @DisplayName("재집계가 실패해도 예외를 전파하지 않아 시작을 막지 않는다")
    @Test
    void doesNotPropagate_whenAggregationFails() {
        LikeCountAggregationUseCase useCase = mock(LikeCountAggregationUseCase.class);
        doThrow(new IllegalStateException("실패")).when(useCase).execute();
        LikeCountStartupAggregator aggregator = new LikeCountStartupAggregator(useCase);

        aggregator.afterSingletonsInstantiated();

        verify(useCase).execute();
    }
}
