package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @DisplayName("주문을 생성할 때, ")
    @Nested
    class CreateOrder {
        @DisplayName("품목이 주어지면, DRAFT 상태 주문이 저장된다.")
        @Test
        void savesDraftOrder_whenItemsAreProvided() {
            // arrange
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            Order result = orderService.createOrder(1L, List.of(new OrderItem(10L, 2, 1000L)));

            // assert
            assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            verify(orderRepository).save(any(Order.class));
        }

        @DisplayName("같은 상품이 여러 품목으로 들어오면, 하나의 품목으로 수량이 합산되어 저장된다.")
        @Test
        void mergesQuantity_whenSameProductIsProvidedMultipleTimes() {
            // arrange
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            Order result = orderService.createOrder(1L, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(10L, 3, 1000L)
            ));

            // assert
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
        }

        @DisplayName("품목이 하나도 없으면, BAD_REQUEST 예외가 발생하고 저장하지 않는다.")
        @Test
        void throwsBadRequestException_whenItemsAreEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.createOrder(1L, List.of());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(orderRepository, never()).save(any(Order.class));
        }
    }

    @DisplayName("주문 단건을 조회할 때, ")
    @Nested
    class GetOrder {
        @DisplayName("존재하는 주문이면, 해당 주문을 반환한다.")
        @Test
        void returnsOrder_whenOrderExists() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            // act
            Order result = orderService.getOrder(1L);

            // assert
            assertThat(result.getUserId()).isEqualTo(1L);
        }

        @DisplayName("존재하지 않는 주문이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenOrderIsAbsent() {
            // arrange
            given(orderRepository.findById(1L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.getOrder(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("사용자 주문 목록을 조회할 때, ")
    @Nested
    class GetOrders {
        @DisplayName("해당 사용자의 주문만 반환한다.")
        @Test
        void returnsOnlyOrdersOfGivenUser() {
            // arrange
            given(orderRepository.findAllByUserId(1L))
                .willReturn(List.of(new Order(1L, List.of(new OrderItem(10L, 2, 1000L)))));

            // act
            List<Order> result = orderService.getOrders(1L);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(1L);
            verify(orderRepository).findAllByUserId(1L);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class ConfirmOrder {
        @DisplayName("DRAFT 상태 주문이면, CONFIRMED 상태와 결제액이 저장된다.")
        @Test
        void savesConfirmedOrder_whenStatusIsDraft() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            given(orderRepository.save(order)).willReturn(order);

            // act
            Order result = orderService.confirmOrder(order);

            // assert
            assertThat(result.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(result.getPaidAmount()).isEqualTo(2000L);
        }

        @DisplayName("이미 확정된 주문이면, CONFLICT 예외가 발생하고 저장하지 않는다.")
        @Test
        void throwsConflictException_whenOrderIsNotDraft() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            order.confirm();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderService.confirmOrder(order);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            verify(orderRepository, never()).save(any(Order.class));
        }
    }
}
