package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.point.PointBalanceService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private ProductService productService;
    @Mock
    private PointBalanceService pointBalanceService;
    @Mock
    private OrderService orderService;

    @Test
    void confirmsOnlyAfterVerifyingEveryStockAndPointBalance() {
        OrderItem item = new OrderItem(10L, "티셔츠", 2_000L, 2);
        OrderModel order = new OrderModel(1L, List.of(item));
        ProductModel product = new ProductModel(1L, "티셔츠", 2_000L, 2);
        PointBalanceModel balance = balanceOf(4_000L);
        when(orderService.getForUpdate(100L)).thenReturn(order);
        when(productService.getForUpdate(10L)).thenReturn(product);
        when(pointBalanceService.getAvailable(1L)).thenReturn(balance);
        when(orderService.save(order)).thenReturn(order);

        OrderModel result = service().confirm(1L, 100L);

        assertThat(result.getStatus()).isEqualTo(com.loopers.domain.order.OrderStatus.CONFIRMED);
        assertThat(result.getPaidAmount()).isEqualTo(4_000L);
        assertThat(product.getStockQuantity()).isZero();
        verify(pointBalanceService).use(1L, 4_000L);
        verify(pointBalanceService).reward(1L, 80L);
        verify(orderService).save(order);
    }

    @Test
    void keepsOrderAndPointUntouchedWhenAnyProductStockIsInsufficient() {
        OrderItem item = new OrderItem(10L, "티셔츠", 2_000L, 2);
        OrderModel order = new OrderModel(1L, List.of(item));
        ProductModel product = new ProductModel(1L, "티셔츠", 2_000L, 1);
        when(orderService.getForUpdate(100L)).thenReturn(order);
        when(productService.getForUpdate(10L)).thenReturn(product);

        assertThatThrownBy(() -> service().confirm(1L, 100L)).isInstanceOf(CoreException.class);

        assertThat(order.getStatus()).isEqualTo(com.loopers.domain.order.OrderStatus.DRAFT);
        assertThat(product.getStockQuantity()).isEqualTo(1);
        verify(pointBalanceService, never()).use(1L, 4_000L);
        verify(orderService, never()).save(order);
    }

    @Test
    void confirmsSmallOrderWithoutCreatingZeroPointReward() {
        OrderItem item = new OrderItem(10L, "양말", 40L, 1);
        OrderModel order = new OrderModel(1L, List.of(item));
        ProductModel product = new ProductModel(1L, "양말", 40L, 1);
        PointBalanceModel balance = balanceOf(40L);
        when(orderService.getForUpdate(100L)).thenReturn(order);
        when(productService.getForUpdate(10L)).thenReturn(product);
        when(pointBalanceService.getAvailable(1L)).thenReturn(balance);
        when(orderService.save(order)).thenReturn(order);

        service().confirm(1L, 100L);

        verify(pointBalanceService).use(1L, 40L);
        verify(pointBalanceService, never()).reward(1L, 0L);
    }

    private OrderConfirmationService service() {
        return new OrderConfirmationService(userService, productService, pointBalanceService, orderService);
    }

    private static PointBalanceModel balanceOf(long amount) {
        PointBalanceModel balance = new PointBalanceModel(1L);
        balance.charge(amount);
        return balance;
    }
}
