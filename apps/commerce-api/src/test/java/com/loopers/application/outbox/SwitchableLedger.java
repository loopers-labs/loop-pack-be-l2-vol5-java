package com.loopers.application.outbox;

import com.loopers.application.order.SentRecord;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 진짜 결과 기록 빈을 감싼다(D-11 이유 2). PASS면 그대로 넘기면서 PUBLISHED 저장에 넘어온 broker 위치를 적는다.
 * HALT_ON_MARK_PUBLISHED면 broker 위치만 적고 진짜 빈에 넘기지 않는다 — broker 확인 뒤, PUBLISHED 저장 전에 멈춘 것과 같다.
 * 다른 호출은 늘 그대로 넘긴다. 상속은 타입을 맞추려는 것뿐이고, 부모의 필드는 쓰지 않는다.
 *
 * <p>relay 테스트(OutboxRelayTest)와 배달 E2E(OrderConfirmedOutboxDeliveryE2ETest)가 함께 쓴다.
 */
class SwitchableLedger extends OutboxDeliveryLedger {

    enum Mode { PASS, HALT_ON_MARK_PUBLISHED }

    private final OutboxDeliveryLedger delegate;
    private final Map<Long, List<SentRecord>> published = new ConcurrentHashMap<>();
    private final List<Long> publishedIds = new CopyOnWriteArrayList<>();
    private final List<SentRecord> halted = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.PASS;

    SwitchableLedger(OutboxDeliveryLedger delegate) {
        super(null, null);
        this.delegate = delegate;
    }

    public void reset() {
        mode = Mode.PASS;
        published.clear();
        publishedIds.clear();
        halted.clear();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public List<SentRecord> publishedFor(Long id) {
        return List.copyOf(published.getOrDefault(id, List.of()));
    }

    public List<Long> publishedIds() {
        return List.copyOf(publishedIds);
    }

    public List<SentRecord> halted() {
        return List.copyOf(halted);
    }

    @Override
    public Optional<ClaimedOutbox> claim(Long id, ZonedDateTime now, ZonedDateTime leaseUntil) {
        return delegate.claim(id, now, leaseUntil);
    }

    @Override
    public void markPublished(Long id, SentRecord sent, ZonedDateTime now) {
        if (mode == Mode.HALT_ON_MARK_PUBLISHED) {
            halted.add(sent);
            return;
        }
        delegate.markPublished(id, sent, now);
        published.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(sent);
        publishedIds.add(id);
    }

    @Override
    public FailureOutcome recordFailure(Long id, String error, ZonedDateTime now) {
        return delegate.recordFailure(id, error, now);
    }

    @Override
    public void resume(String eventId, ZonedDateTime now) {
        delegate.resume(eventId, now);
    }
}
