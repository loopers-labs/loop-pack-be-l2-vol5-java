package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loopers.application.mall.event.AllProductDetailsChangedEvent;
import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class LikeCountAggregationServiceTest {
    private final LikeCountAggregationDao aggregationDao = mock(LikeCountAggregationDao.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final LikeCountAggregationService service = new LikeCountAggregationService(aggregationDao, eventPublisher);

    @DisplayName("전체 재집계 성공 후 전체 상품 상세 변경 이벤트를 발행한다")
    @Test
    void publishesAfterRecount() {
        // act
        service.execute();

        // assert
        InOrder order = inOrder(aggregationDao, eventPublisher);
        order.verify(aggregationDao).recountAll();
        order.verify(eventPublisher).publishEvent(new AllProductDetailsChangedEvent());
    }

    @DisplayName("전체 재집계가 실패하면 변경 이벤트를 발행하지 않는다")
    @Test
    void doesNotPublish_whenRecountFails() {
        // arrange
        doThrow(new IllegalStateException("집계 실패")).when(aggregationDao).recountAll();

        // act & assert
        assertThatThrownBy(service::execute).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(eventPublisher);
    }
}
