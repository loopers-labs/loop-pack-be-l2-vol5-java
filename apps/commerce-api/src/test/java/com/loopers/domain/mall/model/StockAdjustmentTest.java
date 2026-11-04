package com.loopers.domain.mall.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockAdjustmentTest {
    @Test
    @DisplayName("복구는 한 번만 표시하고 성공 처리로 뒤집으려 해도 복구 상태와 수량을 유지한다")
    void restoreOnce() {
        StockAdjustment record = StockAdjustment.create(42, List.of(new StockAdjustment.Item(1, 2)));
        assertThat(record.restore()).isTrue();
        assertThat(record.restore()).isFalse();
        assertThatThrownBy(record::consume).isInstanceOf(DomainException.class);
        assertThat(record.getState()).isEqualTo(StockAdjustment.State.RESTORED);
        assertThat(record.getItems()).containsExactly(new StockAdjustment.Item(1, 2));
    }

    @Test
    @DisplayName("성공 완료는 한 번만 표시하며 잘못된 품목·중복 품목과 성공 뒤 복구를 거절한다")
    void consumeAndValidateItems() {
        StockAdjustment record = StockAdjustment.create(42, List.of(new StockAdjustment.Item(1, 2)));
        assertThat(record.consume()).isTrue();
        assertThat(record.consume()).isFalse();
        assertThatThrownBy(record::restore).isInstanceOf(DomainException.class);
        assertThat(record.getState()).isEqualTo(StockAdjustment.State.CONSUMED);
        assertThatThrownBy(() -> StockAdjustment.create(42, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockAdjustment.create(42,
                List.of(new StockAdjustment.Item(1, 2), new StockAdjustment.Item(1, 3)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockAdjustment.Item(1, 0)).isInstanceOf(DomainException.class);
    }
}
