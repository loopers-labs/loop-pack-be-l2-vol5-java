package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikeCountDeltaBufferTest {
    private final LikeCountDeltaBuffer buffer = new LikeCountDeltaBuffer();

    @DisplayName("같은 상품의 증감분을 누적한다")
    @Test
    void accumulatesDeltasOfSameProduct() {
        buffer.on(new ProductLikeChangedEvent(10L, 1L));
        buffer.on(new ProductLikeChangedEvent(10L, 1L));
        buffer.on(new ProductLikeChangedEvent(20L, -1L));

        assertThat(buffer.drain()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 2L, 20L, -1L));
    }

    @DisplayName("drain은 값을 돌려주고 버퍼를 비운다")
    @Test
    void drainEmptiesBuffer() {
        buffer.on(new ProductLikeChangedEvent(10L, 1L));

        buffer.drain();

        assertThat(buffer.drain()).isEmpty();
    }

    @DisplayName("합이 0인 상품은 drain 결과에서 제외한다")
    @Test
    void drainOmitsZeroSum() {
        buffer.on(new ProductLikeChangedEvent(10L, 1L));
        buffer.on(new ProductLikeChangedEvent(10L, -1L));
        buffer.on(new ProductLikeChangedEvent(20L, 1L));

        assertThat(buffer.drain()).containsExactlyEntriesOf(Map.of(20L, 1L));
    }

    @DisplayName("restore한 증감분은 이후 새 증감분과 합산된다")
    @Test
    void restoreMergesWithNewDeltas() {
        buffer.restore(Map.of(10L, 3L));
        buffer.on(new ProductLikeChangedEvent(10L, -1L));

        assertThat(buffer.drain()).containsExactlyEntriesOf(Map.of(10L, 2L));
    }
}
