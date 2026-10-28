package com.loopers.outbox.adapter.in.event;

import com.loopers.outbox.application.port.in.OutboxPublishUseCase;
import com.loopers.outbox.domain.OutboxAppendedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 발행 기록을 남긴 트랜잭션이 commit된 뒤에만 Kafka로 보낸다 (ADR-W6-02). rollback되면 부르지 않는다 — 거짓 이벤트가 없다.
 * commit과 send 사이에서 프로세스가 멈추면 이벤트가 나가지 않는다(D-1 ②). 그 공백은 기록의 published_at null로 드러나고 W7 relay가 메운다.
 */
@RequiredArgsConstructor
@Component
public class OutboxAppendedEventListener {

    private final OutboxPublishUseCase outboxPublishUseCase;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OutboxAppendedEvent event) {
        outboxPublishUseCase.publish(event);
    }
}
