package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderModelTest {

    @DisplayName("주문을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("품목이 하나 이상이면, DRAFT 상태로 생성된다.")
        @Test
        void createsDraftOrder_whenItemsExist() {
            // act
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 2, 1000L)));

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getUserId()).isEqualTo(1L);
        }

        @DisplayName("품목이 하나도 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenItemsAreEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Order(1L, List.of()));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("같은 productId가 여러 품목으로 들어오면, 수량을 합쳐 하나로 만든다.")
        @Test
        void mergesItems_whenSameProductIdAppearsMultipleTimes() {
            // arrange
            List<Order.OrderItemDraft> drafts = List.of(
                new Order.OrderItemDraft(1L, 2, 1000L),
                new Order.OrderItemDraft(2L, 1, 500L),
                new Order.OrderItemDraft(1L, 3, 1000L)
            );

            // act
            Order order = new Order(1L, drafts);

            // assert
            assertThat(order.getItems()).hasSize(2);
            OrderItem merged = order.getItems().stream()
                .filter(item -> item.getProductId().equals(1L))
                .findFirst()
                .orElseThrow();
            assertThat(merged.getQuantity()).isEqualTo(5);
            assertThat(merged.getUnitPrice()).isEqualTo(1000L);
        }

        @DisplayName("수량이 0 이하인 품목이 있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsZeroOrNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new Order(1L, List.of(new Order.OrderItemDraft(1L, 0, 1000L)))
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("주문 확정 가능 여부를 확인할 때, ")
    @Nested
    class AssertDraft {
        @DisplayName("DRAFT 상태면, 예외가 발생하지 않는다.")
        @Test
        void doesNotThrow_whenStatusIsDraft() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));

            // act & assert
            order.assertDraft();
        }

        @DisplayName("이미 CONFIRMED 상태면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenStatusIsConfirmed() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.confirm(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, order::assertDraft);

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 상태면, CONFIRMED로 전이되고 결제액·확정시각이 저장된다.")
        @Test
        void transitionsToConfirmed_whenStatusIsDraft() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 2, 1000L)));

            // act
            order.confirm(2000L);

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(order.getConfirmedAt()).isNotNull();
        }

        @DisplayName("이미 CONFIRMED 상태면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenAlreadyConfirmed() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.confirm(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.confirm(1000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("PG 결제를 시작할 때, ")
    @Nested
    class StartPgPayment {
        @DisplayName("DRAFT 상태면, PAYMENT_PENDING으로 전이되고 결제 수단이 PG로 기록된다.")
        @Test
        void transitionsToPaymentPending_whenStatusIsDraft() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 2, 1000L)));

            // act
            order.startPgPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaidAmount()).isNull();
        }

        @DisplayName("이미 결제가 진행 중이면, CONFLICT 예외가 발생한다. (주문당 한 시도)")
        @Test
        void throwsConflictException_whenPaymentAlreadyStarted() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.startPgPayment();

            // act
            CoreException result = assertThrows(CoreException.class, order::startPgPayment);

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("과거 POINT로 확정된 주문이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenAlreadyConfirmedByPoint() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.confirm(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, order::startPgPayment);

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("PG 결제 결과를 반영할 때, ")
    @Nested
    class PaymentResult {
        @DisplayName("결제 진행 중에 성공하면, CONFIRMED로 전이되고 결제액·확정시각이 저장된다.")
        @Test
        void transitionsToConfirmed_whenPaymentSucceeds() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 2, 1000L)));
            order.startPgPayment();

            // act
            order.completePayment(2000L);

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(order.getConfirmedAt()).isNotNull();
        }

        @DisplayName("결제 진행 중에 실패하면, PAYMENT_FAILED로 전이된다.")
        @Test
        void transitionsToPaymentFailed_whenPaymentFails() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.startPgPayment();

            // act
            order.failPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isNull();
        }

        @DisplayName("결제 진행 중이 아니면(DRAFT·이미 실패), CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenNotPaymentPending() {
            // arrange
            Order draft = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            Order failed = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            failed.startPgPayment();
            failed.failPayment();

            // act
            CoreException onDraft = assertThrows(CoreException.class, () -> draft.completePayment(1000L));
            CoreException onFailed = assertThrows(CoreException.class, failed::failPayment);

            // assert
            assertThat(onDraft.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(onFailed.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    /**
     * 확정 시각은 정해지는 자리에서 DB가 담을 모양(UTC, 마이크로초)으로 맞춘다(docs/week6/decisions.md D-6, INV-602).
     * 두 확정 경로 모두 같다 — 근거가 "이벤트가 나가는 경로"가 아니라 "확정 시각이 정해지는 자리"이기 때문이다.
     */
    @DisplayName("확정 시각은, ")
    @Nested
    class ConfirmedAt {
        @DisplayName("포인트 확정(confirm)에서 UTC이고 마이크로초 아래가 잘려 있다.")
        @Test
        void isUtcAndTruncatedToMicros_whenConfirmedByPoint() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));

            // act
            order.confirm(1000L);

            // assert
            assertUtcMicros(order.getConfirmedAt());
        }

        @DisplayName("PG 성공 반영(completePayment)에서 UTC이고 마이크로초 아래가 잘려 있다.")
        @Test
        void isUtcAndTruncatedToMicros_whenPaymentCompleted() {
            // arrange
            Order order = new Order(1L, List.of(new Order.OrderItemDraft(1L, 1, 1000L)));
            order.startPgPayment();

            // act
            order.completePayment(1000L);

            // assert
            assertUtcMicros(order.getConfirmedAt());
        }

        private void assertUtcMicros(ZonedDateTime confirmedAt) {
            assertThat(confirmedAt.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(confirmedAt.getNano() % 1_000).isZero();
            assertThat(confirmedAt.truncatedTo(ChronoUnit.MICROS)).isEqualTo(confirmedAt);
        }
    }

    @DisplayName("결제액을 계산할 때, ")
    @Nested
    class CalculateTotalAmount {
        @DisplayName("품목들의 단가×수량 합을 반환한다.")
        @Test
        void returnsSumOfUnitPriceTimesQuantity() {
            // arrange
            List<Order.OrderItemDraft> drafts = List.of(
                new Order.OrderItemDraft(1L, 2, 1000L),
                new Order.OrderItemDraft(2L, 3, 500L)
            );
            Order order = new Order(1L, drafts);

            // act
            long total = order.calculateTotalAmount();

            // assert
            assertThat(total).isEqualTo(2 * 1000L + 3 * 500L);
        }
    }
}
