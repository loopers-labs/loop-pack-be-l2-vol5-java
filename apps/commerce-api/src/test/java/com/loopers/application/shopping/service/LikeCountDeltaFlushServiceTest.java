package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikeCountDeltaFlushServiceTest {
    private final LikeCountDeltaBuffer buffer = new LikeCountDeltaBuffer();
    private final LikeCountAggregationDao aggregationDao = mock(LikeCountAggregationDao.class);
    private final LikeCountDeltaFlushService service = new LikeCountDeltaFlushService(buffer, aggregationDao);

    @DisplayName("누적분이 없으면 DAO를 호출하지 않는다")
    @Test
    void doesNothing_whenBufferEmpty() {
        service.execute();

        verify(aggregationDao, never()).addDeltas(anyMap());
    }

    @DisplayName("누적분이 있으면 그대로 addDeltas를 호출하고 버퍼를 비운다")
    @Test
    void flushesDrainedDeltas() {
        buffer.on(new ProductLikeChangedEvent(10L, 2L));
        buffer.on(new ProductLikeChangedEvent(20L, -1L));

        service.execute();

        verify(aggregationDao).addDeltas(Map.of(10L, 2L, 20L, -1L));
        assertThat(buffer.drain()).isEmpty();
    }

    @DisplayName("DAO가 실패하면 증감분을 버퍼에 되돌리고 예외를 전파한다")
    @Test
    void restoresDeltas_andRethrows_whenDaoFails() {
        buffer.on(new ProductLikeChangedEvent(10L, 2L));
        doThrow(new IllegalStateException("실패")).when(aggregationDao).addDeltas(anyMap());

        assertThatThrownBy(service::execute).isInstanceOf(IllegalStateException.class);

        assertThat(buffer.drain()).containsExactlyEntriesOf(Map.of(10L, 2L));
    }
}
