package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderFacadeTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductService productService;

    @Mock
    private PointRepository pointRepository;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderFacade orderFacade;

    @DisplayName("주문을 생성할 때, ")
    @Nested
    class CreateOrder {
        @DisplayName("상품이 존재하고 수량이 양수이면, 상품의 현재 가격을 단가로 기록해 주문 생성에 위임한다.")
        @Test
        void delegatesToOrderService_whenProductsExistAndQuantitiesArePositive() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(10L)).willReturn(new Product(1L, "상품", new Price(1000L)));
            given(orderService.createOrder(anyLong(), any())).willAnswer(invocation ->
                new Order(invocation.getArgument(0), invocation.getArgument(1)));

            // act
            OrderInfo result = orderFacade.createOrder(1L, List.of(new OrderCommand.Item(10L, 2)));

            // assert
            assertThat(result.totalAmount()).isEqualTo(2000L);
            verify(orderService).createOrder(anyLong(), any());
        }

        @DisplayName("같은 상품이 여러 품목으로 들어오면, 수량이 합산된 하나의 품목으로 주문된다.")
        @Test
        void mergesQuantity_whenSameProductIsProvidedMultipleTimes() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(10L)).willReturn(new Product(1L, "상품", new Price(1000L)));
            given(orderService.createOrder(anyLong(), any())).willAnswer(invocation ->
                new Order(invocation.getArgument(0), invocation.getArgument(1)));

            // act
            OrderInfo result = orderFacade.createOrder(1L, List.of(
                new OrderCommand.Item(10L, 2),
                new OrderCommand.Item(10L, 3)
            ));

            // assert
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).quantity()).isEqualTo(5);
        }

        @DisplayName("생성 시점에는 재고를 차감하지 않는다.")
        @Test
        void doesNotDeductStock_whenOrderIsCreated() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(10L)).willReturn(new Product(1L, "상품", new Price(1000L)));
            given(orderService.createOrder(anyLong(), any())).willAnswer(invocation ->
                new Order(invocation.getArgument(0), invocation.getArgument(1)));

            // act
            orderFacade.createOrder(1L, List.of(new OrderCommand.Item(10L, 2)));

            // assert
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }

        @DisplayName("상품이 없거나 삭제되었으면, NOT_FOUND 예외가 전파되고 주문되지 않는다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsentOrDeleted() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(10L))
                .willThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.createOrder(1L, List.of(new OrderCommand.Item(10L, 2)));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @DisplayName("수량이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNotPositive() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(10L)).willReturn(new Product(1L, "상품", new Price(1000L)));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.createOrder(1L, List.of(new OrderCommand.Item(10L, 0)));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class ConfirmOrder {
        @DisplayName("본인 주문이고 재고·포인트가 충분하면, 재고→포인트→확정 순서로 처리된다.")
        @Test
        void deductsStockThenPointThenConfirms_whenRequesterIsOwnerAndResourcesAreSufficient() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            Point point = new Point(1L);
            point.charge(5000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));
            given(orderService.confirmOrder(order)).willAnswer(invocation -> {
                order.confirm();
                return order;
            });

            // act
            OrderInfo result = orderFacade.confirmOrder(1L, 100L);

            // assert
            assertThat(result.paidAmount()).isEqualTo(2000L);
            assertThat(point.getBalance()).isEqualTo(3000L);
            verify(productService).deductStock(10L, 2);
            verify(orderService).confirmOrder(order);
        }

        @DisplayName("같은 상품이 합산된 주문이면, 합산된 총수량 기준으로 재고를 차감한다.")
        @Test
        void deductsStockByMergedTotalQuantity() {
            // arrange
            Order order = new Order(1L, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(10L, 3, 1000L)
            ));
            Point point = new Point(1L);
            point.charge(10000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));
            given(orderService.confirmOrder(order)).willAnswer(invocation -> {
                order.confirm();
                return order;
            });

            // act
            orderFacade.confirmOrder(1L, 100L);

            // assert
            verify(productService).deductStock(10L, 5);
        }

        @DisplayName("요청자가 주문자와 다르면, 주문 존재를 알리지 않고 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenRequesterIsNotOwner() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            given(userRepository.existsById(9L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.confirmOrder(9L, 100L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }

        @DisplayName("포인트가 부족하면, CONFLICT 예외가 발생하고 주문은 DRAFT 로 유지된다.")
        @Test
        void keepsDraftStatus_whenPointIsInsufficient() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            Point point = new Point(1L);
            point.charge(500L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.confirmOrder(1L, 100L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            verify(orderService, never()).confirmOrder(any(Order.class));
        }

        @DisplayName("재고가 부족하면, CONFLICT 예외가 전파되고 포인트는 차감되지 않는다.")
        @Test
        void doesNotDeductPoint_whenStockIsInsufficient() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            Point point = new Point(1L);
            point.charge(5000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);
            given(productService.deductStock(10L, 2))
                .willThrow(new CoreException(ErrorType.CONFLICT, "재고가 부족합니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.confirmOrder(1L, 100L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(point.getBalance()).isEqualTo(5000L);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
        }

        @DisplayName("이미 확정된 주문이면, CONFLICT 예외가 전파된다.")
        @Test
        void throwsConflictException_whenAlreadyConfirmed() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            order.confirm();
            Point point = new Point(1L);
            point.charge(5000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));
            given(orderService.confirmOrder(order))
                .willThrow(new CoreException(ErrorType.CONFLICT, "이미 확정된 주문입니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.confirmOrder(1L, 100L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("주문을 조회할 때, ")
    @Nested
    class GetOrders {
        @DisplayName("요청자 본인의 주문만 목록에 포함된다.")
        @Test
        void returnsOnlyOwnOrders() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(orderService.getOrders(1L))
                .willReturn(List.of(new Order(1L, List.of(new OrderItem(10L, 2, 1000L)))));

            // act
            List<OrderInfo> result = orderFacade.getOrders(1L);

            // assert
            assertThat(result).hasSize(1);
            verify(orderService).getOrders(1L);
        }

        @DisplayName("본인 주문이 아닌 상세를 조회하면, 주문 존재를 알리지 않고 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenRequesterIsNotOwner() {
            // arrange
            Order order = new Order(1L, List.of(new OrderItem(10L, 2, 1000L)));
            given(userRepository.existsById(9L)).willReturn(true);
            given(orderService.getOrder(100L)).willReturn(order);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                orderFacade.getOrder(9L, 100L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
