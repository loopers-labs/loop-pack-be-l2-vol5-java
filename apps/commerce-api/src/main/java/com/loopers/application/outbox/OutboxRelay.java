package com.loopers.application.outbox;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 전송할 차례가 된 Outbox 를 브로커로 보내고 결과를 기록하는 한 회차를 실행한다. 메소드에 트랜잭션을 열지 않고 DB 구간만
 * {@link TransactionTemplate} 으로 감싸, 브로커 확인을 기다리는 동안 DB 연결과 잠금을 쥐지 않는다. Outbox 의 상태는 이
 * 클래스의 상태 트랜잭션에서만 바꾼다.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxService outboxService;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration retryInterval;
    private final TransactionTemplate transaction;

    public OutboxRelay(
        OutboxService outboxService,
        OutboxPublisher outboxPublisher,
        PlatformTransactionManager transactionManager,
        Clock clock,
        @Value("${outbox.relay.batch-size}") int batchSize,
        @Value("${outbox.relay.retry-interval}") Duration retryInterval
    ) {
        this.outboxService = outboxService;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retryInterval = retryInterval;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 한 회차를 실행한다. 회차가 겹치지 않는 것은 스케줄러가 보장하므로 여러 스레드에서 동시에 부르지 않는다. */
    public void relayOnce() {
        List<Outbox> targets = startAttempts();
        int published = 0;
        for (Outbox target : targets) {
            if (publish(target)) {
                published++;
            }
        }
        log.info("Outbox relay 회차 완료. 선택={}, 성공={}, 실패={}", targets.size(), published, targets.size() - published);
    }

    // 전송 전에 시도 횟수를 올려 둬서, 보내기 전에 멈춘 시도도 센다.
    private List<Outbox> startAttempts() {
        return transaction.execute(status -> {
            List<Outbox> due = outboxService.findDueForDelivery(ZonedDateTime.now(clock), batchSize);
            due.forEach(Outbox::startAttempt);
            return due;
        });
    }

    private boolean publish(Outbox target) {
        try {
            outboxPublisher.publish(target);
        } catch (OutboxPublishException e) {
            recordFailure(target, e);
            return false;
        }
        recordPublished(target);
        return true;
    }

    private void recordPublished(Outbox target) {
        transaction.executeWithoutResult(status -> outboxService.get(target.getId()).markPublished(ZonedDateTime.now(clock)));
    }

    private void recordFailure(Outbox target, OutboxPublishException error) {
        log.warn("Outbox 전송 실패. eventId={}, orderId={}, attemptCount={}, error={}",
            target.getEventId(), target.getOrderId(), target.getAttemptCount(), error.getMessage());
        transaction.executeWithoutResult(status ->
            outboxService.get(target.getId()).scheduleRetry(ZonedDateTime.now(clock).plus(retryInterval), error.getMessage())
        );
    }
}
