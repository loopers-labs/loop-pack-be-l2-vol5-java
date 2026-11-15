package com.loopers.support.outbox;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderOutboxBacklog;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * relay의 한 단계를 끝까지 수행한 뒤 다음 단계로 못 가게 막는 테스트 hook (W7 과제 6절, design.md 4-2절).
 *
 * <p>relay가 부르는 Outbox 단계(대상 조회 → 시도 기록 → [broker 전송] → PUBLISHED 저장 또는 실패 기록)를 실제 서비스 빈(트랜잭션 프록시)에 그대로 넘기다가,
 * 멈출 단계에 이르면 그 단계를 수행하지 않고 {@link RelayStopped}로 회차 흐름을 끝낸다. 앞 단계는 실제로 끝났고, 막은 단계와 그 뒤는 일어나지 않는다.
 * relay는 이 예외를 잡지 않으므로 실패 기록도 남지 않는다.
 *
 * <p>예외로 relay의 실행 흐름을 끝낸 것이지 프로세스 강제 종료(OS kill)가 아니다. 재시작은 새 relay 객체로 하고 같은 DB·broker·eventId를 그대로 쓴다.
 * 이 hook은 Outbox 상태를 직접 바꾸지 않는다.
 */
public final class RelayStopHook extends OrderOutboxService {

    public enum Stage {
        /** relay가 보낼 행을 고르는 첫 단계. 여기서 멈추면 "확정 commit 뒤 relay 실행 전 중단"이다. */
        SELECT_DUE,
        /** broker 확인 뒤 PUBLISHED를 저장하는 단계. 여기서 멈추면 "broker 확인 뒤 PUBLISHED 저장 전 중단"이다. */
        MARK_PUBLISHED
    }

    /** hook이 relay 흐름을 멈췄다는 신호. 프로세스 종료가 아니다. */
    public static final class RelayStopped extends RuntimeException {

        RelayStopped(Stage stage, String detail) {
            super("relay 흐름을 " + stage + " 단계 직전에 멈춤: " + detail);
        }
    }

    private final OrderOutboxService delegate;
    private final Stage stopAt;
    private final List<String> stops = new CopyOnWriteArrayList<>();

    private RelayStopHook(Stage stopAt, OrderOutboxService delegate) {
        // 저장소·직렬화기를 직접 쓰지 않는다. 모든 단계는 delegate(트랜잭션 프록시)로 넘긴다
        super(null, null, null);
        this.stopAt = stopAt;
        this.delegate = delegate;
    }

    /** {@code stage} 단계에 이르면 그 단계를 수행하지 않고 멈추는 hook. 그 전 단계는 {@code delegate}가 실제로 수행한다. */
    public static RelayStopHook before(Stage stage, OrderOutboxService delegate) {
        return new RelayStopHook(stage, delegate);
    }

    /** 멈춘 지점의 기록. 비어 있으면 hook이 한 번도 작동하지 않았다. */
    public List<String> stops() {
        return List.copyOf(stops);
    }

    @Override
    public OrderOutboxModel append(OrderConfirmedEvent event, Instant recordedAt) {
        throw new UnsupportedOperationException("relay hook은 주문 확정 경로에 쓰지 않는다");
    }

    @Override
    public BackfillResult backfill(Long orderId, Instant recordedAt) {
        throw new UnsupportedOperationException("relay hook은 누락 복구 경로에 쓰지 않는다");
    }

    @Override
    public List<String> findDueEventIds(Instant now, int limit) {
        stopIfAt(Stage.SELECT_DUE, "now=" + now);
        return delegate.findDueEventIds(now, limit);
    }

    @Override
    public Optional<OrderOutboxMessage> startAttempt(String eventId) {
        return delegate.startAttempt(eventId);
    }

    @Override
    public boolean markPublished(String eventId, Instant publishedAt) {
        stopIfAt(Stage.MARK_PUBLISHED, "eventId=" + eventId);
        return delegate.markPublished(eventId, publishedAt);
    }

    @Override
    public boolean recordFailure(String eventId, int attempt, String error, Instant nextAttemptAt) {
        return delegate.recordFailure(eventId, attempt, error, nextAttemptAt);
    }

    @Override
    public OrderOutboxBacklog backlog() {
        return delegate.backlog();
    }

    private void stopIfAt(Stage stage, String detail) {
        if (stage == stopAt) {
            stops.add(stage + " " + detail);
            throw new RelayStopped(stage, detail);
        }
    }
}
