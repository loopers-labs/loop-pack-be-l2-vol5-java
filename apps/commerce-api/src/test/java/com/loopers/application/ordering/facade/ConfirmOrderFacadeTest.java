package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.mall.service.ProductService;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.service.OrderService;
import com.loopers.application.pay.service.WalletService;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ConfirmOrderFacadeTest {
    private final OrderService orderService = mock(OrderService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final ProductService productService = mock(ProductService.class);
    private final ConfirmOrderFacade facade = new ConfirmOrderFacade(orderService, walletService, productService);

    @DisplayName("주문 확정")
    @Nested
    class Execute {
        @DisplayName("주문 -> 지갑 -> 재고 -> 결제 -> 확정 순서로 호출하고 확정된 주문과 기록을 반환한다")
        @Test
        void callsStepsInOrder_andReturnsConfirmedOrderAndRecord() {
            Order order = draftOrder();
            Wallet wallet = Wallet.restore(1L, 10_000L);
            given(orderService.lockForConfirm(1L)).willReturn(order);
            given(walletService.lockByUserId(1L)).willReturn(wallet);
            given(orderService.confirm(order)).willAnswer(invocation -> {
                order.confirm();
                return order;
            });

            ConfirmOrderResult result = facade.execute(new ConfirmOrderCommand(1L));

            InOrder inOrder = inOrder(orderService, walletService, productService);
            inOrder.verify(orderService).lockForConfirm(1L);
            inOrder.verify(walletService).lockByUserId(1L);
            inOrder.verify(productService).decreaseStocks(Map.of(10L, 3, 20L, 1));
            inOrder.verify(walletService).pay(wallet, order);
            inOrder.verify(orderService).confirm(order);
            assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(result.paymentAmount()).isEqualTo(3_500L);
            assertThat(result.paymentStatus()).isEqualTo(OrderRecordStatus.PAID);
        }

        @DisplayName("재고 차감에는 품목 등장 순서를 유지한 상품별 합산 수량을 넘긴다")
        @Test
        void passesAggregatedQuantities_inItemAppearanceOrder() {
            Order order = draftOrder();
            given(orderService.lockForConfirm(1L)).willReturn(order);
            given(walletService.lockByUserId(1L)).willReturn(Wallet.restore(1L, 10_000L));
            given(orderService.confirm(order)).willAnswer(invocation -> {
                order.confirm();
                return order;
            });

            facade.execute(new ConfirmOrderCommand(1L));

            verify(productService).decreaseStocks(order.quantitiesByProductId());
            assertThat(order.quantitiesByProductId().keySet()).containsExactly(10L, 20L);
        }

        @DisplayName("주문 단계가 실패하면 지갑·상품·결제·확정을 호출하지 않는다")
        @Test
        void stopsAtOrderStep_whenLockForConfirmFails() {
            given(orderService.lockForConfirm(1L)).willThrow(new DomainException(DomainErrorCode.ORDER_ALREADY_CONFIRMED));

            assertThatThrownBy(() -> facade.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
            verify(walletService, never()).lockByUserId(anyLong());
            verify(productService, never()).decreaseStocks(anyMap());
            verify(walletService, never()).pay(any(), any());
            verify(orderService, never()).confirm(any());
        }

        @DisplayName("상품 단계가 실패하면 결제와 확정을 호출하지 않는다")
        @Test
        void stopsAtProductStep_whenDecreaseStocksFails() {
            Order order = draftOrder();
            given(orderService.lockForConfirm(1L)).willReturn(order);
            given(walletService.lockByUserId(1L)).willReturn(Wallet.restore(1L, 10_000L));
            willThrow(new DomainException(DomainErrorCode.INSUFFICIENT_STOCK))
                .given(productService).decreaseStocks(anyMap());

            assertThatThrownBy(() -> facade.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            verify(walletService, never()).pay(any(), any());
            verify(orderService, never()).confirm(any());
        }

        @DisplayName("결제 단계가 실패하면 주문을 확정하지 않는다")
        @Test
        void stopsAtPayStep_whenPayFails() {
            Order order = draftOrder();
            Wallet wallet = Wallet.restore(1L, 100L);
            given(orderService.lockForConfirm(1L)).willReturn(order);
            given(walletService.lockByUserId(1L)).willReturn(wallet);
            given(walletService.pay(wallet, order)).willThrow(new DomainException(DomainErrorCode.INSUFFICIENT_POINT));

            assertThatThrownBy(() -> facade.execute(new ConfirmOrderCommand(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            verify(orderService, never()).confirm(any());
        }
    }

    private Order draftOrder() {
        return Order.restore(1L, 1L, OrderStatus.DRAFT, List.of(
            OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L),
            OrderItem.restore(20L, "상품B", 500L, 1, 500L),
            OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)
        ), 3_500L, Instant.now(), null);
    }
}
