package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {
    @DisplayName("주문을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("품목이 주어지면, DRAFT 상태로 생성된다.")
        @Test
        void createsOrderAsDraft_whenItemsAreProvided() {
            // act
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // assert
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(order.getUserId()).isEqualTo(1L);
            assertThat(order.getItems()).hasSize(1);
        }

        @DisplayName("품목이 하나도 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenItemsAreEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Order(1L, List.of());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("같은 상품이 여러 품목으로 들어오면, 하나의 품목으로 수량이 합산된다.")
        @Test
        void mergesQuantity_whenSameProductIsProvidedMultipleTimes() {
            // act
            Order order = new Order(1L, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(10L, 3, 1000L)
            ));

            // assert
            assertThat(order.getItems()).hasSize(1);
            assertThat(order.getItems().get(0).getQuantity()).isEqualTo(5);
        }

        @DisplayName("합계 금액은 모든 품목 금액의 합과 같다.")
        @Test
        void calculatesTotalAmountAsSumOfItems() {
            // act
            Order order = new Order(1L, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(20L, 1, 3000L)
            ));

            // assert
            assertThat(order.getTotalAmount()).isEqualTo(5000L);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 상태이면, CONFIRMED 상태가 되고 결제액이 기록된다.")
        @Test
        void confirmsOrder_whenStatusIsDraft() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // act
            order.confirm();

            // assert
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
        }

        @DisplayName("이미 확정된 주문이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenAlreadyConfirmed() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            order.confirm();

            // act
            CoreException result = assertThrows(CoreException.class, order::confirm);

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("이미 확정된 주문의 재확정이 거절되어도, 기존 결제액은 유지된다.")
        @Test
        void keepsPaidAmount_whenReconfirmFails() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            order.confirm();

            // act
            assertThrows(CoreException.class, order::confirm);

            // assert
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
        }

        @DisplayName("확정 전에는, DRAFT 상태이고 결제액이 기록되지 않는다.")
        @Test
        void hasNoPaidAmount_whenNotConfirmed() {
            // act
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // assert
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(order.getPaidAmount()).isZero();
        }
    }

    @DisplayName("주문 소유자를 확인할 때, ")
    @Nested
    class Ownership {
        @DisplayName("주문자와 요청자가 같으면, 본인 주문으로 판별된다.")
        @Test
        void identifiesAsOwner_whenRequesterIsOrderer() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // assert
            assertThat(order.isOwnedBy(1L)).isTrue();
        }

        @DisplayName("주문자와 요청자가 다르면, 본인 주문이 아닌 것으로 판별된다.")
        @Test
        void identifiesAsNotOwner_whenRequesterIsNotOrderer() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // assert
            assertThat(order.isOwnedBy(9L)).isFalse();
        }
    }
}
