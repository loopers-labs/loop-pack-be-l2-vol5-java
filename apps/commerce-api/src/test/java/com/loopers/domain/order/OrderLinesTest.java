package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLinesTest {

    @DisplayName("ORD-01·P-01 같은 상품은 합산한다: [p1×2, p1×3, p2×1] → p1×5, p2×1")
    @Test
    void mergesSameProduct() {
        // act
        OrderLines lines = OrderLines.of(List.of(
            new OrderLines.Line(1L, 2),
            new OrderLines.Line(1L, 3),
            new OrderLines.Line(2L, 1)
        ));

        // assert
        assertThat(lines.quantities()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 5, 2L, 1));
        assertThat(lines.productIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @DisplayName("ORD-01 품목이 없으면 거절한다.")
    @Test
    void rejectsEmpty() {
        // act & assert
        assertThatThrownBy(() -> OrderLines.of(List.of()))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> OrderLines.of(null))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("ORD-01 수량이 0 이하인 품목이 있으면 거절한다.")
    @Test
    void rejectsNonPositiveQuantity() {
        // act & assert
        assertThatThrownBy(() -> OrderLines.of(List.of(new OrderLines.Line(1L, 0))))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> OrderLines.of(List.of(new OrderLines.Line(1L, 2), new OrderLines.Line(2L, -1))))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("ORD-01·P-18 같은 상품의 합산 수량이 int 범위를 넘으면 거절한다.")
    @Test
    void rejectsMergedQuantityOverflow() {
        // act & assert
        assertThatThrownBy(() -> OrderLines.of(List.of(
            new OrderLines.Line(1L, Integer.MAX_VALUE),
            new OrderLines.Line(1L, 1)
        )))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
    }
}
