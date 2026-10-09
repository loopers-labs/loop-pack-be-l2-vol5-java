package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderRepository;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.StockRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.transaction.TransactionRetryExecutor;
import com.loopers.user.domain.PointRepository;
import com.loopers.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderConflictHandlingTest {

    @DisplayName("주문 충돌의 재시도를 소진해도 새 경계에서 실제 확정 상태를 확인한 경우에만 재확정 오류를 반환한다.")
    @Test
    void verifiesCommittedOrderStatusBeforeReportingAlreadyConfirmed() {
        OrderRepository orders = mock(OrderRepository.class);
        TransactionRetryExecutor retry = mock(TransactionRetryExecutor.class);
        Order confirmed = new Order(1L, List.of(new OrderItem(1L, "Air", 1, 1_000L)));
        confirmed.confirm(1_000L, ZonedDateTime.now());
        when(orders.findById(10L)).thenReturn(Optional.of(confirmed));
        when(retry.execute(any(), eq(2)))
            .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 10L));
        when(retry.execute(any(), eq(0))).thenAnswer(invocation -> {
            Supplier<?> query = invocation.getArgument(0);
            return query.get();
        });
        OrderUseCase useCase = new OrderUseCase(orders, mock(ProductRepository.class), mock(UserRepository.class),
            mock(PointRepository.class), mock(StockRepository.class), retry, mock(EntityManager.class));

        CoreException failure = assertThrows(CoreException.class, () -> useCase.confirm(1L, 10L));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED);
        verify(retry).execute(any(), eq(0));
        verify(orders).findById(10L);
    }
}
