package com.loopers.application.order;

import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxBacklog;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Outbox의 PENDING 행을 broker로 보내는 단일 relay (W7 design.md 4절). 이 클래스에는 트랜잭션이 없다.
 *
 * <ul>
 *   <li>한 회차: 재시도 시각이 된 PENDING을 (다음 시도 시각, id) 순으로 최대 batch-size개 고르고 한 건씩 차례로 보낸다.</li>
 *   <li>한 건: 짧은 트랜잭션에서 시도를 기록 → 트랜잭션 밖에서 저장된 key·payload를 send하고 broker 확인을 기다림 → 별도 트랜잭션에서
 *       PUBLISHED 또는 실패(다음 시도 시각·오류)를 기록한다. broker를 기다리는 동안 DB 트랜잭션·잠금을 잡지 않는다.</li>
 *   <li>상태를 바꾸는 곳은 이 회차 흐름뿐이다. send future의 늦은 callback은 상태를 바꾸지 않는다.</li>
 *   <li>한 프로세스 안에서는 회차가 겹치지 않는다(scheduler와 수동 실행 모두 같은 잠금을 {@code tryLock}한다).
 *       여러 인스턴스 사이의 소유권은 다루지 않으므로 relay는 한 인스턴스에서만 켠다.</li>
 * </ul>
 *
 * <p>broker 확인 뒤 PUBLISHED 저장 전에 멈추면 행은 PENDING으로 남아 같은 사건을 다시 보낸다. 누락 대신 중복을 택하고, 중복 효과는 consumer가 막는다.
 */
@Slf4j
@Component
public class OrderOutboxRelay {

    /** 한 회차의 결과. 다른 회차가 실행 중이라 건너뛰었으면 {@code executed}가 거짓이다. */
    public record Run(boolean executed, int picked, int published, int failed) {

        static Run skipped() {
            return new Run(false, 0, 0, 0);
        }
    }

    private enum Outcome { PUBLISHED, FAILED, NOT_PENDING }

    private final OrderOutboxService orderOutboxService;
    private final OrderOutboxSender orderOutboxSender;
    private final OrderOutboxRelayProperties properties;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public OrderOutboxRelay(
        OrderOutboxService orderOutboxService,
        OrderOutboxSender orderOutboxSender,
        OrderOutboxRelayProperties properties,
        Clock clock
    ) {
        this.orderOutboxService = orderOutboxService;
        this.orderOutboxSender = orderOutboxSender;
        this.properties = properties;
        this.clock = clock;
    }

    /** 재시도 시각이 된 PENDING을 한 회차 보낸다. 다른 회차가 실행 중이면 기다리지 않고 건너뛴다. */
    public Run runOnce() {
        if (!running.tryLock()) {
            log.info("Outbox relay 회차를 건너뜀: 다른 회차가 실행 중");
            return Run.skipped();
        }
        try {
            List<String> due = orderOutboxService.findDueEventIds(clock.instant(), properties.batchSize());
            int published = 0;
            int failed = 0;
            for (String eventId : due) {
                switch (relay(eventId)) {
                    case PUBLISHED -> published++;
                    case FAILED -> failed++;
                    case NOT_PENDING -> {
                    }
                }
            }
            if (failed > 0) {
                OrderOutboxBacklog backlog = orderOutboxService.backlog();
                log.warn("Outbox relay 회차: picked={}, published={}, failed={}, pending={}, oldestPendingCreatedAt={}, maxAttempt={}",
                    due.size(), published, failed, backlog.pendingCount(), backlog.oldestPendingCreatedAt(), backlog.maxAttemptCount());
            } else if (!due.isEmpty()) {
                log.info("Outbox relay 회차: picked={}, published={}, failed={}", due.size(), published, failed);
            }
            return new Run(true, due.size(), published, failed);
        } finally {
            running.unlock();
        }
    }

    /**
     * 운영 재개: eventId로 지정한 PENDING 한 건을 다음 시도 시각을 기다리지 않고 지금 보낸다. 반복 실패의 원인을 고친 뒤 쓴다.
     * 공개 API로 노출하지 않는다. 로컬의 운영 도구·테스트가 직접 부른다. 회차 실행 중이면 기다리지 않고 건너뛴다.
     * 없거나 이미 PUBLISHED인 행은 보내지 않는다.
     */
    public Run resume(String eventId) {
        if (!running.tryLock()) {
            log.info("Outbox 재개를 건너뜀: 다른 회차가 실행 중. eventId={}", eventId);
            return Run.skipped();
        }
        try {
            Outcome outcome = relay(eventId);
            log.info("Outbox 재개: eventId={}, result={}", eventId, outcome);
            return switch (outcome) {
                case PUBLISHED -> new Run(true, 1, 1, 0);
                case FAILED -> new Run(true, 1, 0, 1);
                case NOT_PENDING -> new Run(true, 0, 0, 0);
            };
        } finally {
            running.unlock();
        }
    }

    /** 아직 broker 확인을 저장하지 못한 행의 수·가장 오래 기다린 기록 시각·최대 시도 수. 소비 완료 여부는 알려 주지 않는다. */
    public OrderOutboxBacklog backlog() {
        return orderOutboxService.backlog();
    }

    private Outcome relay(String eventId) {
        Optional<OrderOutboxMessage> started = orderOutboxService.startAttempt(eventId);
        if (started.isEmpty()) {
            return Outcome.NOT_PENDING;
        }
        OrderOutboxMessage message = started.get();
        return switch (send(message)) {
            case OrderEventPublication.Sent sent -> {
                // broker가 받았다. 이 저장 전에 멈추거나 저장이 실패하면 행은 PENDING으로 남아 다음 회차가 같은 사건을 다시 보낸다
                orderOutboxService.markPublished(eventId, clock.instant());
                log.info("Outbox 발행 확인: eventId={}, orderId={}, attempt={}, topic={}, partition={}, offset={}",
                    eventId, message.orderId(), message.attempt(), sent.topic(), sent.partition(), sent.offset());
                yield Outcome.PUBLISHED;
            }
            case OrderEventPublication.Failed failed -> {
                Instant nextAttemptAt = clock.instant().plus(properties.backoffAfter(message.attempt()));
                orderOutboxService.recordFailure(eventId, message.attempt(), failed.reason(), nextAttemptAt);
                if (message.attempt() >= properties.alertAfterAttempts()) {
                    // 일시 장애가 아닐 수 있다. 행은 지우지 않고 상한 간격으로 계속 시도하며, 원인을 고친 뒤 resume(eventId)로 바로 보낼 수 있다
                    log.error("Outbox 발행이 반복해서 실패한다(원인 확인 필요): eventId={}, orderId={}, attempt={}, nextAttemptAt={}, reason={}",
                        eventId, message.orderId(), message.attempt(), nextAttemptAt, failed.reason());
                } else {
                    log.warn("Outbox 발행 미확인(같은 행을 다시 보낸다): eventId={}, orderId={}, attempt={}, nextAttemptAt={}, reason={}",
                        eventId, message.orderId(), message.attempt(), nextAttemptAt, failed.reason());
                }
                yield Outcome.FAILED;
            }
        };
    }

    private OrderEventPublication send(OrderOutboxMessage message) {
        try {
            return orderOutboxSender.send(message);
        } catch (RuntimeException e) {
            // 계약상 던지지 않지만, 던져도 이 시도를 실패로 기록하고 같은 행을 다시 보낸다
            return new OrderEventPublication.Failed("send 중 예외: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
