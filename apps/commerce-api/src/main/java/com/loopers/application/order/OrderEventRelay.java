package com.loopers.application.order;

import com.loopers.domain.order.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주문 이벤트 Outbox relay. 저장된 PENDING 행을 저장된 key·payload 그대로 broker 로 보내고, broker 확인 뒤 별도 트랜잭션에서 PUBLISHED 로 바꾼다.
 * 한 회차: 대상 고르기·시도 기록 (트랜잭션) → 한 건씩 send 하고 확인을 기다림 (트랜잭션 밖) → 결과 저장 (건마다 트랜잭션).
 * 회차는 이 객체 안에서 겹치지 않는다 (주기 실행과 운영 재개가 같은 lock). 다른 프로세스의 relay 와는 조율하지 않는다: 한 인스턴스에서만 켠다 (outbox.relay.enabled).
 * 상태를 바꾸는 곳은 이 회차뿐이다. 확인 대기를 넘긴 send 가 나중에 성공해도 그 결과로 상태를 바꾸지 않는다 (future 에 콜백을 달지 않는다).
 * PUBLISHED 는 broker 기록까지다. 소비 완료가 아니다.
 */
@Slf4j
@Component
public class OrderEventRelay {
    private final OrderEventOutboxProcessor processor;
    private final OrderEventSender sender;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public OrderEventRelay(OrderEventOutboxProcessor processor, OrderEventSender sender, OutboxRelayProperties properties, Clock clock) {
        this.processor = processor;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 회차 결과. skipped 는 다른 회차가 도는 중이라 아무것도 하지 않음, aborted 는 결과 저장에 실패해 회차를 멈춤 (남은 행은 PENDING 그대로).
     */
    public record Run(boolean skipped, int claimed, int published, int failed, int blocked, boolean aborted) {
        static final Run SKIPPED = new Run(true, 0, 0, 0, 0, false);
    }

    private enum Outcome { PUBLISHED, FAILED, BLOCKED, ABORTED }

    /** 재시도 시각이 된 PENDING 을 batchSize 건까지 보낸다. 다른 회차가 돌고 있으면 기다리지 않고 건너뛴다. */
    public Run runDue() {
        if (!lock.tryLock()) {
            return Run.SKIPPED;
        }
        try {
            Run run = deliver(processor.claimDue(now()));
            if (run.claimed() > 0) {
                log.info("OrderEvent relay 회차 [claimed = {}, published = {}, failed = {}, blocked = {}, aborted = {}]",
                    run.claimed(), run.published(), run.failed(), run.blocked(), run.aborted());
            }
            return run;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 운영 재개: eventId 하나를 한 번 보낸다. BLOCKED 면 같은 행을 PENDING 으로 되돌린 뒤, 재시도 시각과 무관하게 보낸다.
     * PUBLISHED 면 보내지 않는다. 도는 회차가 있으면 끝날 때까지 기다린다.
     */
    public Run resumeAndRun(String eventId) {
        lock.lock();
        try {
            if (processor.resume(eventId, now())) {
                log.warn("OrderEvent 재개, BLOCKED → PENDING [eventId = {}]", eventId);
            }
            return deliver(processor.claim(eventId, now()).stream().toList());
        } finally {
            lock.unlock();
        }
    }

    private Run deliver(List<OrderEventOutboxProcessor.Claimed> claimed) {
        int published = 0;
        int failed = 0;
        int blocked = 0;
        for (OrderEventOutboxProcessor.Claimed target : claimed) {
            switch (deliverOne(target)) {
                case PUBLISHED -> published++;
                case FAILED -> failed++;
                case BLOCKED -> blocked++;
                case ABORTED -> {
                    return new Run(false, claimed.size(), published, failed, blocked, true);
                }
            }
        }
        return new Run(false, claimed.size(), published, failed, blocked, false);
    }

    private Outcome deliverOne(OrderEventOutboxProcessor.Claimed target) {
        OrderEventSender.Sent sent;
        try {
            sent = sender.send(target.messageKey(), target.payload()).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return fail(target, "TimeoutException: broker 확인 대기 " + properties.sendTimeout() + " 초과 (기록 여부 모름)");
        } catch (ExecutionException e) {
            return fail(target, describe(e.getCause() == null ? e : e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("OrderEvent relay 중단, 확인 대기 중 interrupt. 행은 PENDING [eventId = {}]", target.eventId());
            return Outcome.ABORTED;
        } catch (RuntimeException e) {
            return fail(target, describe(e));
        }
        try {
            processor.markPublished(target.eventId(), now(), sent);
        } catch (RuntimeException e) {
            log.error("broker 확인 뒤 발행 확인 저장 실패, 행은 PENDING 이라 같은 사건을 다시 보낸다 [eventId = {}, broker = {}-{}@{}]",
                target.eventId(), sent.topic(), sent.partition(), sent.offset(), e);
            return Outcome.ABORTED;
        }
        log.info("OrderEvent 발행 확인 [eventId = {}, attempt = {}, broker = {}-{}@{}]",
            target.eventId(), target.attempt(), sent.topic(), sent.partition(), sent.offset());
        return Outcome.PUBLISHED;
    }

    private Outcome fail(OrderEventOutboxProcessor.Claimed target, String error) {
        OutboxStatus status;
        try {
            status = processor.recordFailure(target.eventId(), now(), error);
        } catch (RuntimeException e) {
            log.error("OrderEvent 전송 실패 기록 실패, 행은 PENDING [eventId = {}, error = {}]", target.eventId(), error, e);
            return Outcome.ABORTED;
        }
        if (status == OutboxStatus.BLOCKED) {
            log.error("OrderEvent BLOCKED, 연속 실패 {}회. eventId 로 재개해야 다시 보낸다 [eventId = {}, attempt = {}, error = {}]",
                properties.maxFailures(), target.eventId(), target.attempt(), error);
            return Outcome.BLOCKED;
        }
        log.warn("OrderEvent 전송 실패, 재시도 예정 [eventId = {}, attempt = {}, error = {}]", target.eventId(), target.attempt(), error);
        return Outcome.FAILED;
    }

    /** 예외 종류와 메시지만. payload 는 넣지 않는다. */
    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
