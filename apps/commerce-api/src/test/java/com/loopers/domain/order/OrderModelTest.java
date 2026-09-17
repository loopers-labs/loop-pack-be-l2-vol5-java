package com.loopers.domain.order;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** AG-06 주문. INV-06, INV-07, INV-08, INV-09, INV-12, ST-03. */
class OrderModelTest {

    private static OrderModel.Line line(long productId, int quantity, long unitPrice) {
        return new OrderModel.Line(productId, quantity, unitPrice);
    }

    @DisplayName("[INV-06][INV-12] 생성 직후 DRAFT, 합계 = Σ(단가 × 수량), 결제액·결제 결과 없음.")
    @Test
    void create_computesTotal_andStartsAsDraft() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L), line(20L, 1, 500L)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(2_500L);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).lineAmount()).isEqualTo(2_000L);
    }

    @DisplayName("[INV-08][ASM-11] 같은 상품 품목은 수량을 합쳐 한 품목으로 저장한다.")
    @Test
    void create_mergesSameProduct() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L), line(20L, 1, 500L), line(10L, 3, 1_000L)));

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).getProductId()).isEqualTo(10L);
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(order.getTotalAmount()).isEqualTo(5_500L);
    }

    @DisplayName("[INV-12][ER-12 EMPTY_ORDER_ITEMS] 품목이 없으면(null·빈 목록) 생성되지 않는다.")
    @Test
    void create_throwsEmptyOrderItems() {
        assertThrowsErrorType(() -> OrderModel.create(1L, null), ErrorType.EMPTY_ORDER_ITEMS);
        assertThrowsErrorType(() -> OrderModel.create(1L, List.of()), ErrorType.EMPTY_ORDER_ITEMS);
    }

    @DisplayName("[INV-07][ER-13 INVALID_QUANTITY] 어느 품목의 수량이 누락·0 이하면 생성되지 않는다.")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void create_throwsInvalidQuantity(Integer quantity) {
        assertThrowsErrorType(
            () -> OrderModel.create(1L, List.of(line(10L, 1, 100L), new OrderModel.Line(20L, quantity, 100L))),
            ErrorType.INVALID_QUANTITY);
    }

    @DisplayName("[INV-06][ER-14 AMOUNT_OUT_OF_RANGE] 항목 금액이 표현 범위를 넘으면 생성되지 않는다 (ASM-05).")
    @Test
    void create_throwsAmountOutOfRange_onLineOverflow() {
        assertThrowsErrorType(() -> OrderModel.create(1L, List.of(line(10L, 2, Long.MAX_VALUE))), ErrorType.AMOUNT_OUT_OF_RANGE);
    }

    @DisplayName("[INV-06][ER-14 AMOUNT_OUT_OF_RANGE] 합계가 표현 범위를 넘으면 생성되지 않는다.")
    @Test
    void create_throwsAmountOutOfRange_onTotalOverflow() {
        assertThrowsErrorType(
            () -> OrderModel.create(1L, List.of(line(10L, 1, Long.MAX_VALUE), line(20L, 1, 1L))),
            ErrorType.AMOUNT_OUT_OF_RANGE);
    }

    @DisplayName("[INV-06] 합계가 정확히 표현 범위 상한이면 허용된다. 0원 상품도 허용 (ASM-04).")
    @Test
    void create_allowsExactMax_andZeroPrice() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 1, Long.MAX_VALUE), line(20L, 3, 0L)));

        assertThat(order.getTotalAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @DisplayName("[ST-03][INV-09] confirm() 으로 DRAFT → CONFIRMED. 결제액 = 합계, 확정 시각 기록.")
    @Test
    void confirm_transitionsToConfirmed() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L)));

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(order.getTotalAmount());
        assertThat(order.getConfirmedAt()).isNotNull();
    }

    @DisplayName("[ST-03][ER-15 ORDER_NOT_DRAFT] CONFIRMED → CONFIRMED 는 금지된 전이. CONFIRMED 유지.")
    @Test
    void confirm_throwsOrderNotDraft_whenAlreadyConfirmed() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L)));
        order.confirm();
        var firstConfirmedAt = order.getConfirmedAt();

        assertThrowsErrorType(order::confirm, ErrorType.ORDER_NOT_DRAFT);
        assertThrowsErrorType(order::ensureDraft, ErrorType.ORDER_NOT_DRAFT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(firstConfirmedAt);
    }

    @DisplayName("[ASM-09] 주문의 소유자 판정.")
    @Test
    void isOwnedBy() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 1, 100L)));

        assertThat(order.isOwnedBy(1L)).isTrue();
        assertThat(order.isOwnedBy(2L)).isFalse();
    }
}
