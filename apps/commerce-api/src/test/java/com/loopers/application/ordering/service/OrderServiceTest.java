package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.ordering.command.OrderCommand;
import com.loopers.application.ordering.result.OrderResult;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class OrderServiceTest {

    @DisplayName("주문 생성")
    @Nested
    class Create {
        @DisplayName("품목별 스냅샷과 합계를 저장한다")
        @Test
        void savesOrder_withSnapshotItemsAndTotalAmount() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            Product product1 = product(1L, "상품1", 1_000L);
            Product product2 = product(2L, "상품2", 3_000L);
            given(productRepository.findById(1L)).willReturn(Optional.of(product1));
            given(productRepository.findById(2L)).willReturn(Optional.of(product2));
            given(orderRepository.save(any(Order.class))).willAnswer(OrderServiceTest::assignId);
            OrderService service = new OrderService(orderRepository, productRepository);
            OrderCommand.Create command = new OrderCommand.Create(1L,
                List.of(new OrderCommand.Item(1L, 2), new OrderCommand.Item(2L, 1)));

            // act
            OrderResult result = service.execute(command);

            // assert
            assertThat(result.totalAmount()).isEqualTo(5_000L);
            assertThat(result.items()).hasSize(2);
            verify(orderRepository).save(any(Order.class));
        }

        @DisplayName("같은 상품이 여러 번 포함되면 수량을 합산해 하나의 품목으로 저장한다")
        @Test
        void mergesDuplicateProductQuantities() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            Product product = product(1L, "상품1", 1_000L);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));
            given(orderRepository.save(any(Order.class))).willAnswer(OrderServiceTest::assignId);
            OrderService service = new OrderService(orderRepository, productRepository);
            OrderCommand.Create command = new OrderCommand.Create(1L,
                List.of(new OrderCommand.Item(1L, 2), new OrderCommand.Item(1L, 3)));

            // act
            OrderResult result = service.execute(command);

            // assert
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).quantity()).isEqualTo(5);
            assertThat(result.items().get(0).amount()).isEqualTo(5_000L);
        }

        @DisplayName("없는 상품이 포함되면 저장 없이 거절한다")
        @Test
        void rejectsCreate_whenProductDoesNotExist() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(productRepository.findById(1L)).willReturn(Optional.empty());
            OrderService service = new OrderService(orderRepository, productRepository);
            OrderCommand.Create command = new OrderCommand.Create(1L, List.of(new OrderCommand.Item(1L, 1)));

            // act & assert
            assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.PRODUCT_NOT_FOUND);
            verify(orderRepository, never()).save(any());
        }

        @DisplayName("삭제된 상품이 포함되면 저장 없이 거절한다")
        @Test
        void rejectsCreate_whenProductIsDeleted() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            Product deletedProduct = product(1L, "상품1", 1_000L);
            deletedProduct.delete();
            given(productRepository.findById(1L)).willReturn(Optional.of(deletedProduct));
            OrderService service = new OrderService(orderRepository, productRepository);
            OrderCommand.Create command = new OrderCommand.Create(1L, List.of(new OrderCommand.Item(1L, 1)));

            // act & assert
            assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
            verify(orderRepository, never()).save(any());
        }

        @DisplayName("중복 수량 합산이 범위를 초과하면 저장 없이 거절한다")
        @Test
        void rejectsCreate_whenMergedQuantityOverflows() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            OrderService service = new OrderService(orderRepository, productRepository);
            OrderCommand.Create command = new OrderCommand.Create(1L, List.of(
                new OrderCommand.Item(1L, Integer.MAX_VALUE),
                new OrderCommand.Item(1L, 1)
            ));

            // act & assert
            assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
            verify(orderRepository, never()).save(any());
        }
    }

    @DisplayName("주문 확정 잠금 조회")
    @Nested
    class LockForConfirm {
        @DisplayName("쓰기 잠금으로 조회한 DRAFT 주문을 반환한다")
        @Test
        void returnsLockedDraftOrder() {
            OrderRepository orderRepository = mock(OrderRepository.class);
            OrderService service = new OrderService(orderRepository, mock(ProductRepository.class));
            Order order = draftOrder();
            given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

            Order result = service.lockForConfirm(1L);

            assertThat(result).isSameAs(order);
            verify(orderRepository).findByIdForUpdate(1L);
        }

        @DisplayName("없는 주문이면 ORDER_NOT_FOUND로 거절한다")
        @Test
        void rejects_whenOrderNotFound() {
            OrderRepository orderRepository = mock(OrderRepository.class);
            OrderService service = new OrderService(orderRepository, mock(ProductRepository.class));
            given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.lockForConfirm(1L))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.ORDER_NOT_FOUND);
        }

        @DisplayName("이미 확정된 주문은 ORDER_ALREADY_CONFIRMED로 거절한다")
        @Test
        void rejects_whenOrderAlreadyConfirmed() {
            OrderRepository orderRepository = mock(OrderRepository.class);
            OrderService service = new OrderService(orderRepository, mock(ProductRepository.class));
            given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(confirmedOrder()));

            assertThatThrownBy(() -> service.lockForConfirm(1L))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
        }
    }

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("주문을 확정하고 저장하며 주문 기록이 주문과 일치한다")
        @Test
        void confirmsAndSavesOrder_withMatchingRecord() {
            OrderRepository orderRepository = mock(OrderRepository.class);
            OrderService service = new OrderService(orderRepository, mock(ProductRepository.class));
            Order order = draftOrder();

            Order result = service.confirm(order);

            assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            OrderRecord record = result.getRecord().orElseThrow();
            assertThat(record.getUserId()).isEqualTo(order.getUserId());
            assertThat(record.getAmount()).isEqualTo(order.getTotalAmount());
            assertThat(record.getStatus()).isEqualTo(OrderRecordStatus.PAID);
            verify(orderRepository).save(order);
        }

        @DisplayName("이미 확정된 주문은 거절하고 저장하지 않는다")
        @Test
        void rejects_andDoesNotSave_whenAlreadyConfirmed() {
            OrderRepository orderRepository = mock(OrderRepository.class);
            OrderService service = new OrderService(orderRepository, mock(ProductRepository.class));
            Order order = confirmedOrder();

            assertThatThrownBy(() -> service.confirm(order))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
            verify(orderRepository, never()).save(any());
        }
    }

    private Order draftOrder() {
        return Order.restore(1L, 1L, OrderStatus.DRAFT,
            List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L, Instant.now(), null);
    }

    private Order confirmedOrder() {
        OrderRecord record = OrderRecord.restore(1L, 1L, 2_000L, OrderRecordStatus.PAID, Instant.now());
        return Order.restore(1L, 1L, OrderStatus.CONFIRMED,
            List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L, Instant.now(), record);
    }

    private static Order assignId(InvocationOnMock invocation) {
        Order order = invocation.getArgument(0);
        return Order.restore(1L, order.getUserId(), order.getStatus(), order.getItems(), order.getTotalAmount(),
            Instant.now(), null);
    }

    private Product product(long id, String name, long price) {
        return Product.restore(id, 1L, name, null, price, 100, false, Instant.now());
    }
}
