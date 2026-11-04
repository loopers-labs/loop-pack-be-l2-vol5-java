package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 유지된 context 에서 호출하는 내부 운영 진입점(설계 W7.3-7). 공개·로컬 HTTP·CLI 는 없다. TX 없이 relay 와 같은 gate 를 쓴다.
 * 이번 단위는 명시 실행 위임과 BLOCKED 재개만 제공한다. 주문 복구(recoverOrders)는 후속 단위다.
 */
@Slf4j
@Component
public class OrderOutboxOperations {

    private final OrderOutboxRelay relay;
    private final OrderOutboxRecoveryTransactionService recoveryTransactions;
    private final OrderOutboxExecutionGate gate;
    private final Clock clock;

    public OrderOutboxOperations(OrderOutboxRelay relay, OrderOutboxRecoveryTransactionService recoveryTransactions,
                                 OrderOutboxExecutionGate gate, @Qualifier(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK) Clock clock) {
        this.relay = relay;
        this.recoveryTransactions = recoveryTransactions;
        this.gate = gate;
        this.clock = clock;
    }

    /** relay 에 그대로 위임한다. gate 는 relay 가 한 번 잡는다. */
    public Result<RowResult> runEvent(String eventId) {
        return relay.runEvent(eventId);
    }

    /** BLOCKED 를 PENDING·회차 0·nextAttemptAt=now 로 재개한다. 자동 send 는 없고 다음 relay 실행이 처리한다. */
    public Result<Transition> resumeBlocked(String eventId) {
        OrderOutboxRelay.requireNoTransaction();
        OrderOutboxRelay.requireCanonicalEventId(eventId);
        Result<Transition> result = gate.tryExecute(() -> recoveryTransactions.resumeBlocked(eventId, OrderOutboxRelay.now(clock)));
        log.info("ORDER_OUTBOX_RESUME eventId={} outcome={} transition={}", eventId, result.outcome(),
            result.value().map(Enum::name).orElse("none"));
        return result;
    }
}
