package com.loopers.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

class OrderTest {
    @ParameterizedTest
    @NullAndEmptySource
    void 품목이_없거나_null이면_주문_생성을_거절한다(List<Order.RequestedItem> items) {
        // arrange
        // 각 입력은 별도의 테스트 실행으로 검증한다.

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 같은_상품_수량을_합치기_전에_각_수량을_검증한다(int quantity) {
        // arrange
        var items =
                List.of(
                        new Order.RequestedItem(10, 3, 2_000),
                        new Order.RequestedItem(10, quantity, 2_000));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    void 같은_상품은_수량을_합친_한_품목으로_만든다() {
        // arrange
        var items =
                List.of(
                        new Order.RequestedItem(10, 2, 2_000),
                        new Order.RequestedItem(10, 3, 2_000));

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
    void 여러_품목의_단가와_수량으로_총액을_계산한다() {
        // arrange
        var items =
                List.of(
                        new Order.RequestedItem(10, 5, 2_000),
                        new Order.RequestedItem(20, 1, 3_000));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualTo(13_000);
    }

    @Test
    void 새_주문은_결제_결과가_없는_DRAFT다() {
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
    void 주문_품목_목록을_외부에서_변경할_수_없다() {
        // arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(10, 1, 2_000)));

        // act
        assertThrows(UnsupportedOperationException.class, () -> order.getItems().clear());

        // assert
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void 확정은_결제_금액과_결과를_기록한다() {
        // arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(10, 2, 2_000)));

        // act
        order.confirm();

        // assert
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000);
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @Test
    void 이미_확정된_주문은_다시_확정할_수_없다() {
        // arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(10, 2, 2_000)));
        order.confirm();

        // act
        CoreException error = assertThrows(CoreException.class, order::confirm);

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(4_000);
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @Test
    void 중복_수량의_합이_int_범위를_넘으면_거절한다() {
        // arrange
        var items =
                List.of(
                        new Order.RequestedItem(10, Integer.MAX_VALUE, 1),
                        new Order.RequestedItem(10, 1, 1));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(error).hasMessage("잘못된 요청입니다.");
    }

    @Test
    void 단가와_수량의_곱이_long_범위를_넘으면_거절한다() {
        // arrange
        var items = List.of(new Order.RequestedItem(10, 2, Long.MAX_VALUE));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ORDER_AMOUNT_OVERFLOW);
        assertThat(error).hasMessage("주문 금액이 허용 범위를 초과했습니다.");
    }

    @Test
    void 품목_금액의_합이_long_범위를_넘으면_거절한다() {
        // arrange
        var items =
                List.of(
                        new Order.RequestedItem(10, 1, Long.MAX_VALUE),
                        new Order.RequestedItem(20, 1, 1));

        // act
        CoreException error = assertThrows(CoreException.class, () -> Order.create(1, items));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ORDER_AMOUNT_OVERFLOW);
        assertThat(error).hasMessage("주문 금액이 허용 범위를 초과했습니다.");
    }

    @ParameterizedTest
    @CsvSource({"2, 2000000000, 4000000000", "1, 9223372036854775807, 9223372036854775807"})
    void 금액은_long_범위까지_계산할_수_있다(int quantity, long unitPrice, long expectedTotal) {
        // arrange
        var items = List.of(new Order.RequestedItem(10, quantity, unitPrice));

        // act
        Order order = Order.create(1, items);

        // assert
        assertThat(order.getTotalAmount()).isEqualTo(expectedTotal);
    }
}
