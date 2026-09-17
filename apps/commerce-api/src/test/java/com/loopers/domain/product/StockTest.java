package com.loopers.domain.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {
    @Test
    @DisplayName("재고 5개에서 2개를 차감하면 새 재고는 3개이고 원본은 유지된다")
    void decreasesStockWithoutChangingOriginal() {
        Stock original = new Stock(5);

        Stock result = original.decrease(2);

        assertThat(result).isEqualTo(new Stock(3));
        assertThat(result).isNotSameAs(original);
        assertThat(original.value()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고 전량을 차감하면 잔여 재고는 0개다")
    void decreasesAllStock() {
        assertThat(new Stock(5).decrease(5)).isEqualTo(new Stock(0));
    }

    @Test
    @DisplayName("재고 5개에서 6개를 차감하면 거절하고 기존 재고를 유지한다")
    void rejectsInsufficientStock() {
        Stock stock = new Stock(5);

        assertThatThrownBy(() -> stock.decrease(6))
            .isInstanceOf(IllegalStateException.class);
        assertThat(stock.value()).isEqualTo(5);
    }

    @ParameterizedTest(name = "재고={0}")
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    @DisplayName("0과 int 최댓값의 재고를 생성할 수 있다")
    void acceptsBoundaryStock(int value) {
        assertThat(new Stock(value).value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "재고={0}")
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    @DisplayName("음수 재고 생성은 거절한다")
    void rejectsNegativeStock(int value) {
        assertThatThrownBy(() -> new Stock(value))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "차감량={0}")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("0 이하의 차감량은 거절하고 기존 재고를 유지한다")
    void rejectsNonPositiveAmount(int amount) {
        Stock stock = new Stock(5);

        assertThatThrownBy(() -> stock.decrease(amount))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.value()).isEqualTo(5);
    }

    @Test
    @DisplayName("재고가 0이면 양수 차감을 거절한다")
    void rejectsDecreasingEmptyStock() {
        assertThatThrownBy(() -> new Stock(0).decrease(1))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("int 최댓값 재고를 전량 차감하면 0개다")
    void decreasesMaximumStock() {
        assertThat(new Stock(Integer.MAX_VALUE).decrease(Integer.MAX_VALUE))
            .isEqualTo(new Stock(0));
    }
}
