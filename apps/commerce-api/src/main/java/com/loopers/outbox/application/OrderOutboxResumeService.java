package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OrderOutboxResumeUseCase;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * BLOCKED 행 재개 (docs/week7/plan.md D-3). 규칙(BLOCKED만, 시도 0, 원인 유지)은 도메인 {@link OrderOutbox#resume}이 지킨다.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class OrderOutboxResumeService implements OrderOutboxResumeUseCase {

    private final OrderOutboxPort orderOutboxPort;
    private final Clock clock;

    @Transactional
    @Override
    public ResumeResult resume(String eventId) {
        OrderOutbox outbox = orderOutboxPort.findByEventId(eventId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "Outbox 행이 없습니다: " + eventId));
        outbox.resume(ZonedDateTime.now(clock));
        OrderOutbox saved = orderOutboxPort.save(outbox);
        log.warn("[OUTBOX_RESUMED] BLOCKED 행을 다시 보낼 차례로 돌렸습니다. eventId={}, orderId={}, lastError={}",
            eventId, saved.getOrderId(), saved.getLastError());
        return new ResumeResult(saved.getEventId(), saved.getOrderId(), saved.getStatus(), saved.getAttemptCount(), saved.getLastError());
    }
}
