package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Stock 은 현재 재고 수량과 변경 규칙을 책임진다.")
class StockTest {

    @DisplayName("생성")
    @Nested
    class Create {
        @DisplayName("0 개는 유효한 재고다.")
        @Test
        void allowsZero() {
            assertThat(Stock.of(0L).getQuantity()).isZero();
        }

        @DisplayName("음수 재고는 만들 수 없다.")
        @Test
        void rejectsNegativeQuantity() {
            assertThatThrownBy(() -> Stock.of(-1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_STOCK_QUANTITY);
        }
    }

    @DisplayName("최종 수량 변경")
    @Nested
    class Change {
        @DisplayName("전달한 최종 수량으로 바꾸고 변경 전후 값을 담은 StockChange 를 반환한다.")
        @Test
        void changesToFinalQuantity() {
            Stock stock = Stock.of(5L);

            StockChange change = stock.change(2L);

            assertAll(
                () -> assertThat(stock.getQuantity()).isEqualTo(2L),
                () -> assertThat(change.beforeQuantity()).isEqualTo(5L),
                () -> assertThat(change.afterQuantity()).isEqualTo(2L),
                () -> assertThat(change.changedQuantity()).isEqualTo(3L)
            );
        }

        @DisplayName("최종 수량 0 으로도 바꿀 수 있다.")
        @Test
        void allowsZeroFinalQuantity() {
            Stock stock = Stock.of(5L);

            stock.change(0L);

            assertThat(stock.getQuantity()).isZero();
        }

        @DisplayName("음수 최종 수량은 거절하고 현재 수량을 유지한다.")
        @Test
        void rejectsNegativeFinalQuantity() {
            Stock stock = Stock.of(5L);

            assertThatThrownBy(() -> stock.change(-1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_STOCK_QUANTITY);
            assertThat(stock.getQuantity()).isEqualTo(5L);
        }
    }

    @DisplayName("차감")
    @Nested
    class Decrease {
        @DisplayName("재고 5 에서 2 를 차감하면 3 이 남는다.")
        @Test
        void decreasesQuantity() {
            Stock stock = Stock.of(5L);

            StockChange change = stock.decrease(2L);

            assertAll(
                () -> assertThat(stock.getQuantity()).isEqualTo(3L),
                () -> assertThat(change.beforeQuantity()).isEqualTo(5L),
                () -> assertThat(change.afterQuantity()).isEqualTo(3L),
                () -> assertThat(change.changedQuantity()).isEqualTo(2L)
            );
        }

        @DisplayName("재고 5 에서 6 을 차감하려 하면 거절하고 재고를 유지한다.")
        @Test
        void rejectsDecreaseOverStock() {
            Stock stock = Stock.of(5L);

            assertThatThrownBy(() -> stock.decrease(6L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);
            assertThat(stock.getQuantity()).isEqualTo(5L);
        }

        @DisplayName("재고 전부를 차감하면 0 이 남는다.")
        @Test
        void allowsDecreasingAll() {
            Stock stock = Stock.of(5L);

            stock.decrease(5L);

            assertThat(stock.getQuantity()).isZero();
        }

        @DisplayName("0 이하의 수량은 차감할 수 없다.")
        @Test
        void rejectsNonPositiveQuantity() {
            Stock stock = Stock.of(5L);

            assertThatThrownBy(() -> stock.decrease(0L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_STOCK_QUANTITY);
            assertThat(stock.getQuantity()).isEqualTo(5L);
        }
    }
}
