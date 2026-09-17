package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Order 는 주문 품목·총액과 확정 상태 전이를 책임진다.")
class OrderModelTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private OrderItemModel item(long productId, long quantity, long unitPrice) {
        return OrderItemModel.of(productId, quantity, Money.of(unitPrice));
    }

    private OrderModel draftOrder() {
        return OrderModel.draft(OWNER_ID, List.of(item(1L, 2L, 2_000L), item(2L, 1L, 3_000L)));
    }

    @DisplayName("주문 품목")
    @Nested
    class Item {
        @DisplayName("품목 금액은 주문 당시 단가 × 수량이다.")
        @Test
        void calculatesAmount() {
            OrderItemModel orderItem = item(1L, 3L, 1_500L);

            assertThat(orderItem.calculateAmount()).isEqualTo(Money.of(4_500L));
        }

        @DisplayName("수량이 1 미만이면 INVALID_ORDER_QUANTITY 로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsNonPositiveQuantity(long quantity) {
            assertThatThrownBy(() -> item(1L, quantity, 1_500L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_ORDER_QUANTITY);
        }
    }

    @DisplayName("생성")
    @Nested
    class Draft {
        @DisplayName("DRAFT 상태로 만들고 품목 금액의 합을 주문 총액으로 가진다.")
        @Test
        void createsDraftWithTotal() {
            OrderModel order = draftOrder();

            assertAll(
                () -> assertThat(order.getUserId()).isEqualTo(OWNER_ID),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getOrderTotal()).isEqualTo(Money.of(7_000L)),
                () -> assertThat(order.getItems()).hasSize(2)
            );
        }

        @DisplayName("DRAFT 주문의 포인트 사용액과 결제액은 아직 없다.")
        @Test
        void hasNoPaymentInformation() {
            OrderModel order = draftOrder();

            assertAll(
                () -> assertThat(order.getUsedPointAmount()).isNull(),
                () -> assertThat(order.getPaymentAmount()).isNull()
            );
        }

        @DisplayName("품목이 없으면 INVALID_ORDER_ITEMS 로 거절한다.")
        @Test
        void rejectsEmptyItems() {
            assertThatThrownBy(() -> OrderModel.draft(OWNER_ID, List.of()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_ORDER_ITEMS);
        }

        @DisplayName("주문 총액이 표현 범위를 넘으면 NUMERIC_OVERFLOW 로 거절한다.")
        @Test
        void rejectsOverflowingTotal() {
            List<OrderItemModel> items = List.of(
                item(1L, 1L, Long.MAX_VALUE),
                item(2L, 1L, 1L)
            );

            assertThatThrownBy(() -> OrderModel.draft(OWNER_ID, items))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.NUMERIC_OVERFLOW);
        }
    }

    @DisplayName("소유자 확인")
    @Nested
    class Ownership {
        @DisplayName("소유자의 요청은 통과한다.")
        @Test
        void allowsOwner() {
            OrderModel order = draftOrder();

            order.requireOwnedBy(OWNER_ID);
        }

        @DisplayName("다른 사용자의 요청은 존재를 노출하지 않고 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUser() {
            OrderModel order = draftOrder();

            assertThatThrownBy(() -> order.requireOwnedBy(OTHER_USER_ID))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
        }
    }

    @DisplayName("확정")
    @Nested
    class Confirm {
        @DisplayName("포인트 사용액을 기록하고 같은 금액을 결제액으로 남기며 CONFIRMED 로 전이한다.")
        @Test
        void confirmsWithPoints() {
            OrderModel order = draftOrder();

            order.confirmWithPoints(7_000L);

            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getUsedPointAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(Money.of(7_000L)),
                () -> assertThat(order.getOrderTotal()).isEqualTo(Money.of(7_000L))
            );
        }

        @DisplayName("포인트 사용액이 주문 총액과 다르면 불변식 위반으로 거절하고 상태를 유지한다.")
        @Test
        void rejectsMismatchedPointAmount() {
            OrderModel order = draftOrder();

            assertThatThrownBy(() -> order.confirmWithPoints(6_000L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INTERNAL_ERROR);
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPaymentAmount()).isNull()
            );
        }

        @DisplayName("이미 확정된 주문은 ORDER_NOT_CONFIRMABLE 로 거절한다.")
        @Test
        void rejectsAlreadyConfirmedOrder() {
            OrderModel order = draftOrder();
            order.confirmWithPoints(7_000L);

            assertThatThrownBy(order::requireConfirmable)
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);
        }

        @DisplayName("이미 확정된 주문을 다시 확정하려 해도 상태와 결제 정보가 덮어써지지 않는다.")
        @Test
        void keepsPaymentInformationOnReconfirm() {
            OrderModel order = draftOrder();
            order.confirmWithPoints(7_000L);

            assertThatThrownBy(() -> order.confirmWithPoints(7_000L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getUsedPointAmount()).isEqualTo(7_000L)
            );
        }

        @DisplayName("DRAFT 주문은 확정 가능하다.")
        @Test
        void allowsDraftOrder() {
            OrderModel order = draftOrder();

            order.requireConfirmable();
        }
    }
}
