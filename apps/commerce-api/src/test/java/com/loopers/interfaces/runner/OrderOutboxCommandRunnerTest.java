package com.loopers.interfaces.runner;

import com.loopers.application.order.dto.OrderBackfillInfo;
import com.loopers.application.order.dto.OrderOutboxResumeInfo;
import com.loopers.application.order.facade.OrderOutboxOperations;
import com.loopers.domain.order.enums.OrderBackfillResult;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 실행 인자를 운영 동작으로 옮기고 종료 코드를 정함. 동작 자체는 OrderOutboxOperationsTest */
class OrderOutboxCommandRunnerTest {

    private final OrderOutboxOperations operations = mock(OrderOutboxOperations.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final OrderOutboxCommandRunner runner =
        new OrderOutboxCommandRunner(operations, environment, mock(ConfigurableApplicationContext.class));

    @DisplayName("run-once 는 relay 한 회차를 부른다.")
    @Test
    void runsOnce() {
        when(operations.runOnce()).thenReturn(true);

        assertThat(runner.execute("run-once")).isEqualTo(OrderOutboxCommandRunner.SUCCESS);
        verify(operations).runOnce();
    }

    @DisplayName("resume 은 지정한 eventId 를 재개한다.")
    @Test
    void resumesGivenEvent() {
        environment.setProperty("order-outbox.event-id", "order-confirmed:v1:1");
        when(operations.resume("order-confirmed:v1:1"))
            .thenReturn(new OrderOutboxResumeInfo("order-confirmed:v1:1", OrderOutboxStatus.PENDING, true));

        assertThat(runner.execute("resume")).isEqualTo(OrderOutboxCommandRunner.SUCCESS);
    }

    @DisplayName("backfill 은 지정한 주문만 넘기고, 한 주문이라도 오류면 실패 코드로 끝낸다.")
    @Test
    void backfillsGivenOrders() {
        environment.setProperty("order-outbox.order-ids", "1, 2");
        when(operations.backfill(List.of(1L, 2L))).thenReturn(List.of(
            OrderBackfillInfo.of(1L, OrderBackfillResult.RECORDED),
            OrderBackfillInfo.failed(2L, "IllegalStateException: 확정 사실이 없는 주문")
        ));

        assertThat(runner.execute("backfill")).isEqualTo(OrderOutboxCommandRunner.FAILURE);
    }

    @DisplayName("대상이 없거나 모르는 명령이면 아무 동작도 하지 않고 사용법 코드로 끝낸다. 전체 소급은 하지 않는다.")
    @Test
    void rejectsMissingTarget() {
        assertThat(runner.execute("backfill")).isEqualTo(OrderOutboxCommandRunner.USAGE);
        assertThat(runner.execute("resume")).isEqualTo(OrderOutboxCommandRunner.USAGE);
        assertThat(runner.execute("delete-all")).isEqualTo(OrderOutboxCommandRunner.USAGE);
        verifyNoInteractions(operations);
    }
}
