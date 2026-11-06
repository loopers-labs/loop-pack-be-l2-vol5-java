package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderModelTest {

    @DisplayName("주문을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("품목이 주어지면, DRAFT 상태와 품목 합계로 생성된다.")
        @Test
        void createsDraftOrder_whenItemsAreValid() {
            // arrange
            List<OrderItem> items = List.of(new OrderItem(1L, 2, 10_000L), new OrderItem(2L, 1, 5_000L));

            // act
            OrderModel order = new OrderModel(1L, items);

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(25_000L);
            assertThat(order.getItems()).hasSize(2);
        }

        @DisplayName("품목이 비어있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenItemsAreEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new OrderModel(1L, List.of()));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 상태이면, CONFIRMED로 바뀌고 결제액이 기록된다.")
        @Test
        void confirmsOrder_whenStatusIsDraft() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act
            order.confirm(20_000L);

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(20_000L);
        }

        @DisplayName("이미 CONFIRMED 상태이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenAlreadyConfirmed() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            order.confirm(20_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.confirm(20_000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("포인트로 확정하면, 결제수단이 POINT로 기록된다.")
        @Test
        void recordsPointPaymentMethod_whenConfirmedWithPoint() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act
            order.confirm(20_000L);

            // assert
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
        }
    }

    @DisplayName("결제수단 컬럼이 생기기 전에 확정한 주문(저장된 결제수단이 비어 있음)을 읽을 때, ")
    @Nested
    class LegacyPaymentMethod {
        private OrderModel legacyConfirmedOrder() {
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            order.confirm(20_000L);
            ReflectionTestUtils.setField(order, "paymentMethod", null);
            return order;
        }

        @DisplayName("확정된 주문이면, 결제수단을 POINT로 보고 결제액·품목은 그대로다.")
        @Test
        void readsAsPoint_whenConfirmedOrderHasNoStoredPaymentMethod() {
            // act
            OrderModel order = legacyConfirmedOrder();

            // assert
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(20_000L);
            assertThat(order.getItems()).hasSize(1);
        }

        @DisplayName("아직 확정하지 않은 DRAFT 주문은, 결제수단이 비어 있는 그대로다.")
        @Test
        void staysEmpty_whenOrderIsDraft() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act & assert
            assertThat(order.getPaymentMethod()).isNull();
        }

        @DisplayName("PG 결제를 진행한 주문은, 결제 대기·확정·실패 어느 상태에서도 PG로 보이고 POINT로 바뀌지 않는다.")
        @Test
        void staysPg_whenOrderWasPaidWithPg() {
            // arrange
            OrderModel pending = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            pending.startPgPayment();
            OrderModel confirmed = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            confirmed.startPgPayment();
            confirmed.completePgPayment(20_000L);
            OrderModel failed = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            failed.startPgPayment();
            failed.failPgPayment();

            // act & assert
            assertThat(pending.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(confirmed.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(failed.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        }
    }

    @DisplayName("PG 결제를 진행할 때, ")
    @Nested
    class PgPayment {
        @DisplayName("DRAFT 주문의 결제를 시작하면, PAYMENT_PENDING이 되고 결제수단은 PG이며 결제액은 아직 없다.")
        @Test
        void startsPayment_whenStatusIsDraft() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act
            order.startPgPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaidAmount()).isNull();
        }

        @DisplayName("DRAFT가 아닌 주문의 결제를 시작하면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenStartingPaymentOfNonDraftOrder() {
            // arrange
            OrderModel pending = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            pending.startPgPayment();
            OrderModel confirmed = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            confirmed.confirm(20_000L);

            // act
            CoreException afterPending = assertThrows(CoreException.class, pending::startPgPayment);
            CoreException afterConfirm = assertThrows(CoreException.class, confirmed::startPgPayment);

            // assert
            assertThat(afterPending.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(afterConfirm.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("결제 결과를 기다리는 주문이 성공하면, CONFIRMED로 바뀌고 결제액이 기록되며 결제수단은 그대로 PG다.")
        @Test
        void completesPayment_whenPending() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            order.startPgPayment();

            // act
            order.completePgPayment(20_000L);

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(20_000L);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        }

        @DisplayName("결제 결과를 기다리는 주문이 확정 실패하면, PAYMENT_FAILED가 되고 결제액은 없다.")
        @Test
        void failsPayment_whenPending() {
            // arrange
            OrderModel order = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            order.startPgPayment();

            // act
            order.failPgPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isNull();
        }

        @DisplayName("결제 결과를 기다리는 주문이 아니면, 성공·실패를 적용할 수 없고 CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenOrderIsNotWaitingForPayment() {
            // arrange
            OrderModel draft = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            OrderModel failed = new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            failed.startPgPayment();
            failed.failPgPayment();

            // act
            CoreException completeDraft = assertThrows(CoreException.class, () -> draft.completePgPayment(20_000L));
            CoreException failDraft = assertThrows(CoreException.class, draft::failPgPayment);
            CoreException completeFailed = assertThrows(CoreException.class, () -> failed.completePgPayment(20_000L));

            // assert
            assertThat(completeDraft.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(failDraft.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(completeFailed.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }
}
