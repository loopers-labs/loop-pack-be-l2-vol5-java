package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OutboxPublishUseCase;
import com.loopers.outbox.application.port.out.EventOutboxPort;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.domain.OutboxAppendedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.concurrent.Executor;

/**
 * commit된 발행 기록을 보낸다 (D-4, ADR-W6-03).
 * <ul>
 *   <li>비동기 send — 호출 스레드(PG callback 요청·reconcile 스케줄러)는 broker 응답을 기다리지 않는다.</li>
 *   <li>결과 표시는 {@link #RESULT_EXECUTOR}에서 한다. send 콜백은 producer I/O 스레드에서 오므로 거기서 DB를 쓰면 모든 send가 늦어진다.</li>
 *   <li>실패해도 주문·결제·재고를 되돌리지 않고 PG를 다시 부르지 않는다. 기록의 published_at이 null로 남아 누락 조회에 잡힌다.</li>
 * </ul>
 */
@Slf4j
@Service
public class OutboxPublishService implements OutboxPublishUseCase {

    public static final String RESULT_EXECUTOR = "outboxResultExecutor";

    private final MessageSendPort messageSendPort;
    private final EventOutboxPort eventOutboxPort;
    private final Executor resultExecutor;

    public OutboxPublishService(
        MessageSendPort messageSendPort,
        EventOutboxPort eventOutboxPort,
        @Qualifier(RESULT_EXECUTOR) Executor resultExecutor
    ) {
        this.messageSendPort = messageSendPort;
        this.eventOutboxPort = eventOutboxPort;
        this.resultExecutor = resultExecutor;
    }

    @Override
    public void publish(OutboxAppendedEvent event) {
        try {
            messageSendPort.send(event.topic(), event.key(), event.payload())
                .whenCompleteAsync((position, error) -> {
                    if (error == null) {
                        eventOutboxPort.markPublished(event.eventId(), position.partition(), position.offset(), ZonedDateTime.now());
                        log.info("이벤트 발행 성공. eventId={}, topic={}, partition={}, offset={}",
                            event.eventId(), event.topic(), position.partition(), position.offset());
                    } else {
                        recordFailure(event, error);
                    }
                }, resultExecutor);
        } catch (RuntimeException e) {
            // send() 호출 자체가 실패해도(직렬화·설정 오류 등) 확정은 그대로 둔다
            recordFailure(event, e);
        }
    }

    private void recordFailure(OutboxAppendedEvent event, Throwable error) {
        log.error("이벤트 발행 실패 — 주문은 그대로 두고 미발행으로 남깁니다. eventId={}, topic={}, key={}",
            event.eventId(), event.topic(), event.key(), error);
        try {
            eventOutboxPort.markFailed(event.eventId(), describe(error));
        } catch (RuntimeException e) {
            log.error("발행 실패 원인 기록도 실패했습니다. published_at이 null이라 누락 조회에는 잡힙니다. eventId={}", event.eventId(), e);
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String text = cause.getClass().getName() + ": " + cause.getMessage();
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
}
