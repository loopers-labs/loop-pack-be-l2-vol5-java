package com.loopers.order.domain;

import com.loopers.common.domain.Money;
import com.loopers.product.domain.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OrderModelTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 16, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final Map<Long, ProductSnapshot> SNAPSHOTS = Map.of(
        1L, new ProductSnapshot(1L, "에어맥스", Money.of(1_000)),
        2L, new ProductSnapshot(2L, "에어포스", Money.of(2_000))
    );

    private static OrderModel draftOrder(Long userId) {
        OrderLines lines = OrderLines.of(List.of(
            new OrderLines.Line(1L, 2),
            new OrderLines.Line(1L, 3),
            new OrderLines.Line(2L, 1)
        ));
        return OrderModel.create(userId, lines, SNAPSHOTS);
    }

    @DisplayName("주문을 만들 때, ")
    @Nested
    class Create {

        @DisplayName("ORD-01 합산된 품목마다 상품명·단가를 복사하고, 합계는 단가×수량의 합(1,000×5 + 2,000×1 = 7,000)이며 DRAFT다.")
        @Test
        void createsDraftWithSnapshotsAndTotal() {
            // act
            OrderModel order = draftOrder(7L);

            // assert
            assertThat(order.getUserId()).isEqualTo(7L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(Money.of(7_000));
            assertThat(order.getPaidAmount()).isNull();
            assertThat(order.getItems())
                .extracting(OrderItemModel::getProductId, OrderItemModel::getProductName, OrderItemModel::getUnitPrice, OrderItemModel::getQuantity)
                .containsExactlyInAnyOrder(
                    tuple(1L, "에어맥스", Money.of(1_000), 5),
                    tuple(2L, "에어포스", Money.of(2_000), 1)
                );
        }
    }

    @DisplayName("주문의 주인을 물을 때, ")
    @Nested
    class Owner {

        @DisplayName("ORD-06 주문한 사용자면 주인이고, 다른 사용자는 주인이 아니다.")
        @Test
        void answersOwnership() {
            // arrange
            OrderModel order = draftOrder(7L);

            // act & assert
            assertThat(order.isOwnedBy(7L)).isTrue();
            assertThat(order.isOwnedBy(8L)).isFalse();
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {

        @DisplayName("ORD-05 결제할 금액은 자기 합계이고, 확정하면 CONFIRMED가 되며 결제액을 합계로 고정한다.")
        @Test
        void confirmsDraftAndFixesPaidAmount() {
            // arrange
            OrderModel order = draftOrder(7L);

            // act
            Money paymentAmount = order.paymentAmount();
            order.confirm(NOW);

            // assert
            assertThat(paymentAmount).isEqualTo(Money.of(7_000));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000));
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
        }

        @DisplayName("ORD-02·P-17 이미 CONFIRMED인 주문은 다시 확정할 수 없고, 확정 시각은 처음 값 그대로다.")
        @Test
        void rejectsConfirmingTwice() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.confirm(NOW);

            // act & assert
            assertThatThrownBy(() -> order.confirm(NOW.plusHours(1)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
            assertThat(order.isDraft()).isFalse();
        }

        @DisplayName("ORD-02 DRAFT 주문은 확정할 수 있는 상태이고, 확인만 해서는 상태가 바뀌지 않는다.")
        @Test
        void checkConfirmableKeepsDraft() {
            // arrange
            OrderModel order = draftOrder(7L);

            // act & assert
            assertThatCode(order::checkConfirmable).doesNotThrowAnyException();
            assertThat(order.isDraft()).isTrue();
            assertThat(order.getPaidAmount()).isNull();
        }

        @DisplayName("ORD-02·P-17 이미 CONFIRMED인 주문은 확정할 수 있는 상태가 아니다.")
        @Test
        void checkConfirmableRejectsConfirmed() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.confirm(NOW);

            // act & assert
            assertThatThrownBy(order::checkConfirmable)
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
        }
    }
    @DisplayName("PG 결제로 확정할 때 (W5 plan.md 3-1), ")
    @Nested
    class PgPayment {

        @DisplayName("DRAFT 주문이 결제를 시작하면 PAYMENT_PENDING·결제 수단 PG가 되고, 결제액·확정 시각은 아직 없다.")
        @Test
        void startPaymentMovesDraftToPaymentPending() {
            // arrange
            OrderModel order = draftOrder(7L);

            // act
            order.startPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaidAmount()).isNull();
            assertThat(order.getConfirmedAt()).isNull();
        }

        @DisplayName("이미 PAYMENT_PENDING인 주문은 다시 결제를 시작할 수 없고(409), 상태는 그대로다.")
        @Test
        void startPaymentRejectsNonDraft() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.startPayment();

            // act & assert
            assertThatThrownBy(order::startPayment)
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("PAYMENT_PENDING 주문은 결제 성공으로 CONFIRMED가 되고 결제액 7,000·확정 시각을 고정하며 true를 돌려준다.")
        @Test
        void confirmPaidConfirmsPendingOrder() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.startPayment();

            // act
            boolean changed = order.confirmPaid(NOW);

            // assert
            assertThat(changed).isTrue();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000));
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
        }

        @DisplayName("ADR-W5-04 ③ 이미 CONFIRMED면 다시 성공을 받아도 false이고 확정 시각은 처음 값이다.")
        @Test
        void confirmPaidIgnoresConfirmedOrder() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.startPayment();
            order.confirmPaid(NOW);

            // act
            boolean changed = order.confirmPaid(NOW.plusHours(1));

            // assert
            assertThat(changed).isFalse();
            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
        }

        @DisplayName("ADR-W5-04 ③ DRAFT·PAYMENT_FAILED 주문은 결제 성공을 받아도 false이고 상태는 그대로다.")
        @Test
        void confirmPaidIgnoresDraftAndFailedOrder() {
            // arrange
            OrderModel draft = draftOrder(7L);
            OrderModel failed = draftOrder(7L);
            failed.startPayment();
            failed.failPayment();

            // act & assert
            assertThat(draft.confirmPaid(NOW)).isFalse();
            assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(draft.getPaidAmount()).isNull();
            assertThat(failed.confirmPaid(NOW)).isFalse();
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(failed.getPaidAmount()).isNull();
        }

        @DisplayName("PAYMENT_PENDING 주문은 확인된 결제 실패로 PAYMENT_FAILED가 되고 true, 결제액·확정 시각은 없다.")
        @Test
        void failPaymentFailsPendingOrder() {
            // arrange
            OrderModel order = draftOrder(7L);
            order.startPayment();

            // act
            boolean changed = order.failPayment();

            // assert
            assertThat(changed).isTrue();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isNull();
            assertThat(order.getConfirmedAt()).isNull();
        }

        @DisplayName("ADR-W5-04 ③ CONFIRMED·PAYMENT_FAILED·DRAFT 주문은 실패를 받아도 false이고 상태는 그대로다.")
        @Test
        void failPaymentIgnoresNonPendingOrder() {
            // arrange
            OrderModel confirmed = draftOrder(7L);
            confirmed.startPayment();
            confirmed.confirmPaid(NOW);
            OrderModel failed = draftOrder(7L);
            failed.startPayment();
            failed.failPayment();
            OrderModel draft = draftOrder(7L);

            // act & assert
            assertThat(confirmed.failPayment()).isFalse();
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(failed.failPayment()).isFalse();
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(draft.failPayment()).isFalse();
            assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
        }
    }
}
