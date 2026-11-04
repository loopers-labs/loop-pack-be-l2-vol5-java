package com.loopers.application.order.facade;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PENDING Outbox 를 broker 확인을 받은 뒤에만 PUBLISHED 로 바꿈 (7주차 설계 4).
 * 상태는 이 회차만 바꾸고 producer callback 은 바꾸지 않음. 늦게 끝난 future 가 새 시도의 결과를 덮지 않게 함
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderOutboxRelay {

    /** 거듭제곱이 넘치지 않게 둔 상한. 이 전에 backoffMax 에 걸림 */
    private static final int MAX_BACKOFF_EXPONENT = 30;

    private final OrderOutboxRelaySteps steps;
    private final OrderConfirmedSender sender;
    private final Clock clock;
    private final OrderOutboxRelayProperties properties;
    /** 스케줄과 수동 실행이 겹치지 않게 함. 프로세스 안에서만 유효함 (D-103, 설계 8) */
    private final ReentrantLock roundLock = new ReentrantLock();

    /** @return 이전 회차가 돌고 있어 건너뛰었으면 false */
    public boolean runOnce() {
        if (!roundLock.tryLock()) {
            log.info("Outbox relay 이전 회차가 진행 중이라 건너뜀");
            return false;
        }
        try {
            for (Long id : steps.findDueIds(now(), properties.batchSize())) {
                if (!relay(id)) {
                    break;
                }
            }
            return true;
        } finally {
            roundLock.unlock();
        }
    }

    /** @return 이번 회차를 이어 가도 되면 true. 일시적 오류면 남은 행도 같은 이유로 timeout 을 기다릴 것이라 멈춤 */
    private boolean relay(Long id) {
        Optional<OrderConfirmedOutbox> started = steps.startAttempt(id, now(), properties.lease());
        if (started.isEmpty()) {
            return true;
        }
        OrderConfirmedOutbox outbox = started.get();
        log.info("주문 확정 이벤트 발행 시도. eventId={} orderId={} attemptCount={}",
            outbox.getEventId(), outbox.getOrderId(), outbox.getAttemptCount());

        OrderConfirmedSendResult result = sender.send(outbox.getMessageKey(), outbox.getPayload());
        if (result instanceof OrderConfirmedSendResult.Acked acked) {
            steps.markPublished(id, now());
            log.info("주문 확정 이벤트 발행. eventId={} orderId={} attemptCount={} topic={} partition={} offset={}",
                outbox.getEventId(), outbox.getOrderId(), outbox.getAttemptCount(), acked.topic(), acked.partition(), acked.offset());
            return true;
        }
        if (result instanceof OrderConfirmedSendResult.Permanent failure) {
            steps.block(id, failure.error());
            log.error("주문 확정 이벤트 발행 실패(영구). BLOCKED 로 격리함. eventId={} orderId={} attemptCount={} error={}",
                outbox.getEventId(), outbox.getOrderId(), outbox.getAttemptCount(), failure.error());
            return true;
        }
        String error = ((OrderConfirmedSendResult.Transient) result).error();
        steps.retryAt(id, now().plus(backoff(outbox.getAttemptCount())), error);
        log.warn("주문 확정 이벤트 발행 실패(일시적). 이번 회차를 멈춤. eventId={} orderId={} attemptCount={} error={}",
            outbox.getEventId(), outbox.getOrderId(), outbox.getAttemptCount(), error);
        return false;
    }

    /** 시도 상한 없이 지수로 늘림. 장애가 길어도 회복 뒤 스스로 따라잡게 함 (D-106) */
    Duration backoff(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), MAX_BACKOFF_EXPONENT);
        Duration delay = properties.backoffInitial().multipliedBy(1L << exponent);
        return delay.compareTo(properties.backoffMax()) > 0 ? properties.backoffMax() : delay;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
