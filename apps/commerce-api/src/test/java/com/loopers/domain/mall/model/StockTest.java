package com.loopers.domain.mall.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StockTest {

    @DisplayName("재고 설정")
    @Nested
    class SetStock {
        @DisplayName("0과 int 최댓값을 재고로 설정한다")
        @Test
        void setsStock_atBoundaries() {
            Stock stock = Stock.of(0);
            Stock changed = stock.set(Integer.MAX_VALUE);

            assertThat(changed.getValue()).isEqualTo(Integer.MAX_VALUE);
            assertThat(stock.getValue()).isZero();
        }

        @DisplayName("음수 재고를 거절하고 기존 값을 유지한다")
        @Test
        void rejectsNegativeStock_andKeepsValue() {
            Stock stock = Stock.of(5);

            assertThatThrownBy(() -> stock.set(-1))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INVALID_STOCK);
            assertThat(stock.getValue()).isEqualTo(5);
        }
    }

    @DisplayName("재고 차감")
    @Nested
    class DecreaseStock {
        @DisplayName("정확한 수량을 차감하면 재고가 0이 된다")
        @Test
        void decreasesExactStock() {
            Stock stock = Stock.of(5);

            Stock changed = stock.decrease(5);

            assertThat(changed.getValue()).isZero();
            assertThat(stock.getValue()).isEqualTo(5);
        }

        @DisplayName("재고보다 하나 많은 수량을 거절하고 기존 값을 유지한다")
        @Test
        void rejectsInsufficientStock_andKeepsValue() {
            Stock stock = Stock.of(5);

            assertThatThrownBy(() -> stock.decrease(6))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(stock.getValue()).isEqualTo(5);
        }
    }
}
