package com.loopers.application.order;

import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 최초 확정 사실을 같은 TX 의 Outbox PENDING 행으로 기록하는 유일한 제품 recorder(설계 W7.2-6).
 * MANDATORY 로 호출자 TX 에 참여하며 새 TX·AFTER_COMMIT insert·send 를 하지 않는다. 생성·직렬화·저장 오류는 그대로 전파해
 * 확정 TX 전체를 실패시킨다. insertAndFlush 는 같은 영속성 컨텍스트의 주문·결제 변경도 SQL 로 반영할 수 있다(W7.2-6a).
 */
@Component
public class OrderOutboxRecordingService implements OrderConfirmedRecorder {

    private final OrderConfirmedMessageEncoder encoder;
    private final OrderConfirmedOutboxRepository repository;
    private final Clock outboxRecordClock;

    public OrderOutboxRecordingService(OrderConfirmedMessageEncoder encoder, OrderConfirmedOutboxRepository repository,
                                       @Qualifier(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK) Clock outboxRecordClock) {
        this.encoder = encoder;
        this.repository = repository;
        this.outboxRecordClock = outboxRecordClock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OrderConfirmed event) {
        var message = encoder.encode(event);
        // DATETIME(6) 와 맞춰 저장 전후 값이 같도록 UTC·마이크로초로 정규화한다. 사건의 confirmedAt 과 다른 기록 시각이다.
        ZonedDateTime firstAttemptAt = ZonedDateTime.now(outboxRecordClock)
            .withZoneSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        repository.insertAndFlush(OrderConfirmedOutboxModel.pending(message, firstAttemptAt));
    }
}
