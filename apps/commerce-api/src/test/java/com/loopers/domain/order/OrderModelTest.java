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
