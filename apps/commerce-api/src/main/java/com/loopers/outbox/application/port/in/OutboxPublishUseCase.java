package com.loopers.outbox.application.port.in;

import com.loopers.outbox.domain.OutboxAppendedEvent;

/**
 * commit된 발행 기록을 Kafka로 보내고, send 콜백 결과를 기록에 표시한다 (ADR-W6-02·03).
 */
public interface OutboxPublishUseCase {

    void publish(OutboxAppendedEvent event);
}
