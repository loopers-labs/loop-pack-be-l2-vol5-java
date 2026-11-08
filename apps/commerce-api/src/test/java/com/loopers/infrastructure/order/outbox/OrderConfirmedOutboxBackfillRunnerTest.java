package com.loopers.infrastructure.order.outbox;

import com.loopers.application.order.event.OrderConfirmedOutboxBackfillService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderConfirmedOutboxBackfillRunnerTest {

    @DisplayName("대상 목록 중 첫 오류에서 실행을 멈추고 뒤의 주문은 보충하지 않는다.")
    @Test
    void stopsAtFirstFailureWithoutProcessingRemainingTargets() {
        OrderConfirmedOutboxBackfillService service = mock(OrderConfirmedOutboxBackfillService.class);
        when(service.backfill(101L)).thenReturn(
            new OrderConfirmedOutboxBackfillService.BackfillResult(101L, "order-confirmed:v1:101", true));
        CoreException failure = new CoreException(ErrorType.INTERNAL_ERROR, "확정 시각이 없는 주문");
        when(service.backfill(102L)).thenThrow(failure);
        OrderConfirmedOutboxBackfillRunner runner = new OrderConfirmedOutboxBackfillRunner(service);
        ReflectionTestUtils.setField(runner, "orderIds", "101,102,103");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isSameAs(failure);

        InOrder order = inOrder(service);
        order.verify(service).backfill(101L);
        order.verify(service).backfill(102L);
        verify(service, never()).backfill(103L);
    }
}
