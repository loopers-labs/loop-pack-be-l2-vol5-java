package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    }
}
