package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {
    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("품목이 없거나 null이면 주문 생성을 거절한다")
    void rejectsEmptyOrder(List<Order.RequestedItem> items) {
        // arrange
        // 각 입력은 별도의 테스트 실행으로 검증한다.

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("같은 상품 수량을 합치기 전에 각 수량을 검증한다")
    void validatesEachQuantityBeforeMerging(int quantity) {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 3, 2_000), new Order.RequestedItem(10, quantity, 2_000));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    @DisplayName("같은 상품은 수량을 합친 한 품목으로 만든다")
    void mergesDuplicateProducts() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 2, 2_000), new Order.RequestedItem(10, 3, 2_000));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getItems()).hasSize(1);
        OrderItem merged = order.getItems().getFirst();
        assertThat(merged.getProductId()).isEqualTo(10);
        assertThat(merged.getQuantity()).isEqualTo(5);
        assertThat(merged.getUnitPrice()).isEqualTo(2_000);
        assertThat(merged.getLineAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("여러 품목의 단가와 수량으로 총액을 계산한다")
    void calculatesTotalForMultipleItems() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 5, 2_000), new Order.RequestedItem(20, 1, 3_000));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualTo(13_000);
    }

    @Test
    @DisplayName("새 주문은 결제 결과가 없는 DRAFT다")
    void createsUnpaidDraft() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 1, 2_000));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getPaymentResult()).isNull();
    }

    @Test
    @DisplayName("주문 품목 목록을 외부에서 변경할 수 없다")
    void protectsOrderItems() {
        // arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(10, 1, 2_000)));

        // act
        assertThrows(UnsupportedOperationException.class, () -> order.getItems().clear());

        // assert
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("확정은 결제 금액과 결과를 기록한다")
    void confirmsPayment() {
        // arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(10, 2, 2_000)));

        // act
        order.confirm();

        // assert
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000);
        assertThat(order.getPaymentResult()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("중복 수량의 합이 int 범위를 넘으면 거절한다")
    void rejectsQuantityOverflow() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, Integer.MAX_VALUE, 1), new Order.RequestedItem(10, 1, 1));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(error).hasMessage("잘못된 요청입니다.");
    }

    @Test
    @DisplayName("단가와 수량의 곱이 long 범위를 넘으면 거절한다")
    void rejectsMultiplicationOverflow() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 2, Long.MAX_VALUE));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ORDER_AMOUNT_OVERFLOW);
        assertThat(error).hasMessage("주문 금액이 허용 범위를 초과했습니다.");
    }

    @Test
    @DisplayName("품목 금액의 합이 long 범위를 넘으면 거절한다")
    void rejectsTotalOverflow() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 1, Long.MAX_VALUE), new Order.RequestedItem(20, 1, 1));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ORDER_AMOUNT_OVERFLOW);
        assertThat(error).hasMessage("주문 금액이 허용 범위를 초과했습니다.");
    }

    @ParameterizedTest
    @CsvSource({"2, 2000000000, 4000000000", "1, 9223372036854775807, 9223372036854775807"})
    @DisplayName("금액은 long 범위까지 계산할 수 있다")
    void calculatesWithinLongRange(int quantity, long unitPrice, long expectedTotal) {
        // arrange
        var items = List.of(new Order.RequestedItem(10, quantity, unitPrice));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getTotalAmount()).isEqualTo(expectedTotal);
    }
}
