package com.loopers.application.ordering.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderItem;
import com.loopers.domain.ordering.order.OrderStatus;
import com.loopers.domain.pay.orderbill.OrderBill;
import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfirmOrderServiceTest {

    @DisplayName("주문 확정")
    @Nested
    class Execute {
        @DisplayName("재고를 차감하고 포인트를 사용한 뒤 기록·주문 상태를 저장한다")
        @Test
        void confirmsOrder_andSavesStockPointAndRecords() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L);
            Product product = product(10L, 1_000L, 5);
            Wallet wallet = Wallet.restore(1L, 5_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act
            ConfirmOrderResult result = service.execute(new ConfirmOrderCommand(1L));

            // assert
            assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(result.paymentAmount()).isEqualTo(2_000L);
            assertThat(product.getStock()).isEqualTo(3);
            assertThat(wallet.getBalance()).isEqualTo(3_000L);
            verify(writer).save(any(ConfirmOrderLoad.class), any(PointBill.class), any(OrderBill.class));
        }

        @DisplayName("없는 주문이면 저장 없이 거절한다")
        @Test
        void rejectsExecute_whenOrderDoesNotExist() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            given(writer.load(999L)).willThrow(new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act & assert
            assertThatThrownBy(() -> service.execute(new ConfirmOrderCommand(999L)))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.ORDER_NOT_FOUND);
            verify(writer, never()).save(any(), any(), any());
        }

        @DisplayName("이미 확정된 주문은 저장 없이 거절한다")
        @Test
        void rejectsExecute_whenOrderIsAlreadyConfirmed() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = confirmedOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 1, 1_000L)), 1_000L);
            Product product = product(10L, 1_000L, 5);
            Wallet wallet = Wallet.restore(1L, 5_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act & assert
            assertThatThrownBy(() -> service.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
            verify(writer, never()).save(any(), any(), any());
        }

        @DisplayName("품목 상품이 삭제됐으면 저장 없이 거절한다")
        @Test
        void rejectsExecute_whenProductIsDeleted() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 1, 1_000L)), 1_000L);
            Product deletedProduct = product(10L, 1_000L, 5);
            deletedProduct.delete();
            Wallet wallet = Wallet.restore(1L, 5_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, deletedProduct), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act & assert
            assertThatThrownBy(() -> service.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
            verify(writer, never()).save(any(), any(), any());
        }

        @DisplayName("재고가 부족하면 포인트 차감 없이 거절한다")
        @Test
        void rejectsExecute_whenStockIsInsufficient() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 6, 6_000L)), 6_000L);
            Product product = product(10L, 1_000L, 5);
            Wallet wallet = Wallet.restore(1L, 10_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act & assert
            assertThatThrownBy(() -> service.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(wallet.getBalance()).isEqualTo(10_000L);
            verify(writer, never()).save(any(), any(), any());
        }

        @DisplayName("포인트가 부족하면 저장 없이 거절한다")
        @Test
        void rejectsExecute_whenPointIsInsufficient() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L);
            Product product = product(10L, 1_000L, 5);
            Wallet wallet = Wallet.restore(1L, 1_999L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act & assert
            assertThatThrownBy(() -> service.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            verify(writer, never()).save(any(), any(), any());
        }

        @DisplayName("현재 상품 가격이 바뀌어도 저장된 주문 합계로 결제한다")
        @Test
        void usesStoredTotalAmount_ignoringCurrentProductPrice() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L);
            Product product = product(10L, 5_000L, 5);
            Wallet wallet = Wallet.restore(1L, 3_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act
            ConfirmOrderResult result = service.execute(new ConfirmOrderCommand(1L));

            // assert
            assertThat(result.paymentAmount()).isEqualTo(2_000L);
            assertThat(wallet.getBalance()).isEqualTo(1_000L);
        }
    }

    private Order draftOrder(long id, long userId, List<OrderItem> items, long totalAmount) {
        return Order.restore(id, userId, OrderStatus.DRAFT, items, totalAmount, Instant.now());
    }

    private Order confirmedOrder(long id, long userId, List<OrderItem> items, long totalAmount) {
        return Order.restore(id, userId, OrderStatus.CONFIRMED, items, totalAmount, Instant.now());
    }

    private Product product(long id, long price, int stock) {
        return Product.restore(id, 1L, "상품", null, price, stock, false, Instant.now());
    }
}
