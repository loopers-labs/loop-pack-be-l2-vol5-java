package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OutboxAppendUseCase;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.application.port.out.TraceContextPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * 주문 Outbox 행을 업무 트랜잭션 안에서 PENDING으로 남긴다 (docs/week7/plan.md "이미 정해진 전제", 노션 과제).
 * MANDATORY — 트랜잭션 밖에서 부르면 IllegalTransactionStateException. REQUIRES_NEW나 after-commit insert는 쓰지 않는다:
 * 따로 commit하면 확정은 됐는데 보낼 사실이 없는 빈틈이 다시 생긴다.
 * <p>
 * W6의 after-commit 직접 발행은 없앴다. commit 뒤 Kafka로 보내는 것은 relay({@link OrderOutboxRelayService}) 한 곳이다.
 */
@RequiredArgsConstructor
@Service
public class OutboxAppendService implements OutboxAppendUseCase {

    private final OrderOutboxPort orderOutboxPort;
    private final Clock clock;
    private final TraceContextPort traceContextPort;

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void append(OrderOutboxEntry entry) {
        // 결과를 반영한 요청의 trace 문맥을 함께 남긴다 — relay가 이 문맥을 부모로 발행한다 (W10 D-12 (나))
        orderOutboxPort.append(entry.eventId(), entry.orderId(), entry.eventType(), entry.schemaVersion(), entry.payload(),
            traceContextPort.currentTraceParent(), ZonedDateTime.now(clock));
    }
}
