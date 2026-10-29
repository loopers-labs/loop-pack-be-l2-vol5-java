package com.loopers.domain.order.entity;

import com.loopers.domain.order.enums.OrderErrorCode;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.loopers.domain.order.enums.PaymentMethod;

class OrderTest {

    private static final Long USER_ID = 1L;
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

    private static Order draft(List<OrderLine> lines) {
        return Order.draft(USER_ID, lines, CREATED_AT);
    }

    private static OrderLine line(long productId, long unitPrice, int quantity) {
        return new OrderLine(productId, "상품" + productId, unitPrice, quantity);
    }

    @DisplayName("DRAFT 주문을 만들 때, ")
    @Nested
    class Draft {
        @DisplayName("품목과 합계를 담은 DRAFT 가 되고, 결제 정보는 비어 있다. (ORD-05)")
        @Test
        void createsDraftWithTotal() {
            // act
            Order order = draft(List.of(line(1L, 1_000L, 2), line(2L, 2_500L, 2)));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull()
            );
        }

        @DisplayName("만료 시각은 생성 시각 + 30분이다. (ORD-08)")
        @Test
        void setsExpiresAtThirtyMinutesLater() {
            // act
            Order order = draft(List.of(line(1L, 1_000L, 1)));

            // assert
            assertThat(order.getExpiresAt()).isEqualTo(CREATED_AT.plusMinutes(30));
        }

        @DisplayName("같은 상품의 품목은 처음 등장한 위치에 하나로 합산한다. (ORD-03, D-33)")
        @Test
        void mergesSameProductAtFirstPosition() {
            // act
            Order order = draft(List.of(line(1L, 1_000L, 2), line(2L, 500L, 1), line(1L, 1_000L, 3)));

            // assert
            assertAll(
                () -> assertThat(order.getItems()).extracting(OrderItem::getProductId, OrderItem::getQuantity)
                    .containsExactly(tuple(1L, 5), tuple(2L, 1)),
                () -> assertThat(order.getTotalAmount()).isEqualTo(5_500L)
            );
        }

        @DisplayName("품목이 없으면, EMPTY_ORDER_ITEMS 예외가 발생한다. (ORD-04)")
        @Test
        void throwsEmptyOrderItems_whenNoLines() {
            CoreException result = assertThrows(CoreException.class, () -> draft(List.of()));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }

        @DisplayName("수량이 1 미만이거나 999 를 넘으면, INVALID_QUANTITY 예외가 발생한다. (ORD-02)")
        @ParameterizedTest
        @ValueSource(ints = {0, -1, 1_000})
        void throwsInvalidQuantity_whenQuantityIsOutOfRange(int quantity) {
            CoreException result = assertThrows(CoreException.class, () -> draft(List.of(line(1L, 1_000L, quantity))));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY);
        }

        @DisplayName("줄마다는 범위 안이어도 합산한 수량이 999 를 넘으면, INVALID_QUANTITY 예외가 발생한다.")
        @Test
        void throwsInvalidQuantity_whenMergedQuantityExceedsLimit() {
            CoreException result = assertThrows(CoreException.class,
                () -> draft(List.of(line(1L, 1_000L, 600), line(1L, 1_000L, 600))));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY);
        }

        @DisplayName("합계 0 원 주문은 허용한다. (0원 상품, 설계 5.2)")
        @Test
        void allowsZeroTotal() {
            assertThat(draft(List.of(line(1L, 0L, 3))).getTotalAmount()).isZero();
        }
    }

    @DisplayName("확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 면, CONFIRMED 가 되고 결제액과 결제 시각을 남긴다.")
        @Test
        void confirmsDraft() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));
            ZonedDateTime paidAt = CREATED_AT.plusMinutes(1);

            // act
            order.confirmWithPoint(7_000L, paidAt);

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(paidAt)
            );
        }

        @DisplayName("이미 확정된 주문이면, ORDER_ALREADY_CONFIRMED 예외가 발생하고 결제 정보는 그대로다. (ORD-07)")
        @Test
        void throwsAlreadyConfirmed_andKeepsPayment() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));
            ZonedDateTime paidAt = CREATED_AT.plusMinutes(1);
            order.confirmWithPoint(7_000L, paidAt);

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.confirmWithPoint(1L, paidAt.plusDays(1)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(paidAt)
            );
        }

        @DisplayName("만료된 주문이면, ORDER_EXPIRED 예외가 발생하고 DRAFT 로 남으며 결제 정보는 비어 있다. (ORD-08)")
        @Test
        void throwsExpired_andKeepsDraft() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.confirmWithPoint(7_000L, CREATED_AT.plusMinutes(30)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull()
            );
        }
    }

    @DisplayName("PG 결제를 시작할 때, ")
    @Nested
    class StartPayment {
        @DisplayName("DRAFT 면, PAYMENT_PENDING 이 되고 결제수단은 PG 이며 결제 정보는 아직 비어 있다. (5주차 설계 2.1)")
        @Test
        void startsPayment() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));

            // act
            order.startPayment(CREATED_AT.plusMinutes(1));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull()
            );
        }

        @DisplayName("만료된 주문이면, ORDER_EXPIRED 예외가 발생하고 DRAFT 로 남는다. (ORD-08)")
        @Test
        void throwsExpired_andKeepsDraft() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.startPayment(CREATED_AT.plusMinutes(30)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPaymentMethod()).isNull()
            );
        }

        @DisplayName("이미 결제를 시작한 주문이면, ORDER_ALREADY_CONFIRMED 예외가 발생한다. (ORD-07)")
        @Test
        void throwsAlreadyConfirmed_whenAlreadyStarted() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));
            order.startPayment(CREATED_AT.plusMinutes(1));

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.startPayment(CREATED_AT.plusMinutes(2)));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED);
        }
    }

    @DisplayName("0원 주문을 결제 없이 확정할 때, (D-68)")
    @Nested
    class ConfirmWithoutPayment {
        @DisplayName("DRAFT 면, CONFIRMED · 결제수단 NONE · 결제액 0 이고 확정한 시각을 남긴다.")
        @Test
        void confirmsWithoutPayment() {
            // arrange
            Order order = draft(List.of(line(1L, 0L, 2)));
            ZonedDateTime now = CREATED_AT.plusMinutes(1);

            // act
            order.confirmWithoutPayment(now);

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.NONE),
                () -> assertThat(order.getPaymentAmount()).isZero(),
                () -> assertThat(order.getPaidAt()).isEqualTo(now)
            );
        }

        @DisplayName("만료된 주문이면, ORDER_EXPIRED 예외가 발생하고 DRAFT 로 남는다. (ORD-08)")
        @Test
        void throwsExpired() {
            Order order = draft(List.of(line(1L, 0L, 1)));

            CoreException result = assertThrows(CoreException.class, () -> order.confirmWithoutPayment(CREATED_AT.plusMinutes(30)));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT)
            );
        }

        @DisplayName("결제할 금액이 있으면, 상태 오류로 막고 DRAFT 로 남는다.")
        @Test
        void throwsIllegalState_whenAmountIsNotZero() {
            Order order = draft(List.of(line(1L, 1_000L, 1)));

            assertThrows(IllegalStateException.class, () -> order.confirmWithoutPayment(CREATED_AT.plusMinutes(1)));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        }
    }

    @DisplayName("PG 결과를 반영할 때, ")
    @Nested
    class SettlePayment {
        private Order pendingOrder() {
            Order order = draft(List.of(line(1L, 7_000L, 1)));
            order.startPayment(CREATED_AT.plusMinutes(1));
            return order;
        }

        @DisplayName("SUCCESS 면, CONFIRMED 가 되고 결제액과 반영 시각을 남긴다. 만료 시각이 지나도 반영한다.")
        @Test
        void confirmsPaid_evenAfterExpiry() {
            // arrange
            Order order = pendingOrder();
            ZonedDateTime paidAt = CREATED_AT.plusHours(2);

            // act
            order.confirmPaid(7_000L, paidAt);

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(paidAt)
            );
        }

        @DisplayName("확정 실패면, PAYMENT_FAILED 가 되고 결제 정보는 비어 있다.")
        @Test
        void failsPayment() {
            // arrange
            Order order = pendingOrder();

            // act
            order.failPayment();

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull()
            );
        }

        @DisplayName("결제 대기가 아닌 주문에 반영하면, 상태 오류로 막고 상태는 그대로다.")
        @Test
        void throwsIllegalState_whenNotPending() {
            // arrange
            Order draft = draft(List.of(line(1L, 7_000L, 1)));
            Order confirmed = pendingOrder();
            confirmed.confirmPaid(7_000L, CREATED_AT.plusMinutes(2));

            // act & assert
            assertAll(
                () -> assertThrows(IllegalStateException.class, () -> draft.confirmPaid(7_000L, CREATED_AT.plusMinutes(2))),
                () -> assertThrows(IllegalStateException.class, draft::failPayment),
                () -> assertThrows(IllegalStateException.class, confirmed::failPayment),
                () -> assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED)
            );
        }
    }

    @DisplayName("확정 시각을 남길 때, (6주차 D-71 · D-82)")
    @Nested
    class ConfirmedAt {
        // 나노초까지 있는 시각을 넣어 DB 정밀도(마이크로초)로 잘리는지 봄
        private final ZonedDateTime at = CREATED_AT.plusMinutes(1).plusNanos(123_456_789);
        private final ZonedDateTime stored = CREATED_AT.plusMinutes(1).plusNanos(123_456_000);

        @DisplayName("PG 반영 · 0원 확정 · 이전 POINT 확정 모두 마이크로초로 자른 같은 값을 결제 시각과 확정 시각에 남긴다.")
        @Test
        void storesSameTruncatedValue_onEveryConfirmPath() {
            // arrange
            Order paid = draft(List.of(line(1L, 7_000L, 1)));
            paid.startPayment(CREATED_AT.plusSeconds(30));
            Order free = draft(List.of(line(1L, 0L, 1)));
            Order point = draft(List.of(line(1L, 7_000L, 1)));

            // act
            paid.confirmPaid(7_000L, at);
            free.confirmWithoutPayment(at);
            point.confirmWithPoint(7_000L, at);

            // assert
            assertThat(List.of(paid, free, point)).allSatisfy(order -> assertAll(
                () -> assertThat(order.getConfirmedAt()).isEqualTo(stored),
                () -> assertThat(order.getPaidAt()).isEqualTo(stored)
            ));
        }

        @DisplayName("DRAFT · 결제 대기 · 결제 실패에서는 비어 있다.")
        @Test
        void isEmpty_beforeConfirmation() {
            // arrange
            Order draft = draft(List.of(line(1L, 7_000L, 1)));
            Order pending = draft(List.of(line(1L, 7_000L, 1)));
            pending.startPayment(CREATED_AT.plusMinutes(1));
            Order failed = draft(List.of(line(1L, 7_000L, 1)));
            failed.startPayment(CREATED_AT.plusMinutes(1));
            failed.failPayment();

            // assert
            assertThat(List.of(draft, pending, failed)).allSatisfy(order -> assertThat(order.getConfirmedAt()).isNull());
        }

        @DisplayName("확정된 주문에 다시 반영해도 바뀌지 않는다.")
        @Test
        void keepsFirstValue_whenAppliedAgain() {
            // arrange
            Order order = draft(List.of(line(1L, 7_000L, 1)));
            order.startPayment(CREATED_AT.plusSeconds(30));
            order.confirmPaid(7_000L, at);

            // act
            assertThrows(IllegalStateException.class, () -> order.confirmPaid(7_000L, at.plusHours(1)));

            // assert
            assertThat(order.getConfirmedAt()).isEqualTo(stored);
        }
    }

    @DisplayName("확정할 수 있는지 검사할 때, 생성 시각 + 30분 직전이면 통과하고 30분부터는 ORDER_EXPIRED 예외가 발생한다. (ORD-08)")
    @Test
    void rejectsFromThirtyMinutes() {
        Order order = draft(List.of(line(1L, 1_000L, 1)));

        order.validateConfirmable(CREATED_AT.plusMinutes(30).minusSeconds(1));
        assertAll(
            () -> assertThat(assertThrows(CoreException.class, () -> order.validateConfirmable(CREATED_AT.plusMinutes(30))).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_EXPIRED),
            () -> assertThat(assertThrows(CoreException.class, () -> order.validateConfirmable(CREATED_AT.plusMinutes(31))).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_EXPIRED)
        );
    }
}
