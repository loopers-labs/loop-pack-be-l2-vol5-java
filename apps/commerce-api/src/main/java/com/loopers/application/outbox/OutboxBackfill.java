package com.loopers.application.outbox;

import com.loopers.application.order.OrderConfirmedOutbox;
import com.loopers.application.outbox.OutboxBackfillJudge.Decision;
import com.loopers.application.outbox.OutboxBackfillJudge.OrderFacts;
import com.loopers.application.outbox.OutboxBackfillJudge.RowFacts;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 6주차에 빠진 OrderConfirmed 이벤트를 저장된 사실로 Outbox 행으로 만든다(docs/week7/decisions.md D-5, INV-706).
 *
 * <p>진입점은 로컬 전용이다 — HTTP API로 열지 않고, 테스트와 로컬 도구가 이 빈 메서드를 직접 부른다(D-5, D-15).
 * 대상은 직접 지정한 주문 하나다. 지난 주문 전체를 자동으로 소급하지 않는다.
 *
 * - 재료: 저장된 주문(상태·confirmed_at·품목)과 orderId로 계산한 eventId뿐이다. 현재 상품 정보와 now()는 payload에 들어가지 않는다.
 *   Clock의 지금은 next_attempt_at의 첫 값에만 쓴다(D-16).
 * - 행은 확정 TX와 같은 팩토리({@link OrderConfirmedOutbox})로만 만든다 — 만드는 지점이 하나여야 같은 eventId에 같은 payload가 간다.
 * - 판단(사실 모자람 / 행 없음 / 같은 행 / 다른 행)은 {@link OutboxBackfillJudge}가 DB 없이 한다(D-14).
 * - PG를 부르지 않고, 주문을 다시 확정하지 않고, 재고 예약을 건드리지 않는다. 쓰는 것은 Outbox 행 하나뿐이다.
 * - 동시에 두 번 돌면 event_id unique가 두 번째 저장을 막는다. 그때는 다시 읽어 "이미 있음"으로 같은 판단을 한다(D-5).
 * - 트랜잭션을 길게 열지 않는다. 조회와 저장은 각자 짧은 TX다 — 저장이 unique에 걸려도 다시 읽기가 새 TX에서 된다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxBackfill {

    /** 복구 결과. 오류 갈래(주문 없음·사실 모자람·다른 행)는 예외로 드러낸다. */
    public enum Result {
        /** 행이 없어 새로 만들었다. relay가 집어 보낸다. */
        CREATED,
        /** 같은 행이 이미 있어 아무것도 쓰지 않았다. */
        ALREADY_PRESENT_SAME
    }

    private final OrderRepository orderRepository;
    private final OrderConfirmedOutbox orderConfirmedOutbox;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    /**
     * @throws CoreException NOT_FOUND — 주문이 없다. CONFLICT — 저장된 사실이 모자라거나, 이미 있는 행이 저장된 사실로 만든 행과 다르다.
     */
    public Result backfill(Long orderId) {
        Order order = orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
        OutboxBackfillJudge.requireStoredFacts(OrderFacts.of(order));

        OutboxEvent candidate = orderConfirmedOutbox.create(order, ZonedDateTime.now(clock));
        RowFacts candidateFacts = RowFacts.of(candidate);
        Decision decision = judge(candidateFacts);
        if (decision == Decision.ALREADY_PRESENT_SAME) {
            log.info("Outbox 복구: 같은 행이 이미 있어 쓰지 않음 eventId={}, orderId={}", candidate.getEventId(), orderId);
            return Result.ALREADY_PRESENT_SAME;
        }

        try {
            outboxEventRepository.save(candidate);
        } catch (DataIntegrityViolationException e) {
            // 그 사이 다른 복구가 같은 eventId로 먼저 저장했다. 다시 읽어 같은 판단을 한다 — 다르면 여기서 CONFLICT로 드러난다.
            if (judge(candidateFacts) != Decision.ALREADY_PRESENT_SAME) {
                throw e;
            }
            log.info("Outbox 복구: 동시에 저장된 같은 행이 있어 쓰지 않음 eventId={}, orderId={}", candidate.getEventId(), orderId);
            return Result.ALREADY_PRESENT_SAME;
        }
        log.info("Outbox 복구: 새 행 저장 eventId={}, orderId={}", candidate.getEventId(), orderId);
        return Result.CREATED;
    }

    private Decision judge(RowFacts candidate) {
        Optional<RowFacts> existing = outboxEventRepository.findByEventId(candidate.eventId()).map(RowFacts::of);
        try {
            return OutboxBackfillJudge.decide(candidate, existing);
        } catch (CoreException e) {
            log.warn("Outbox 복구 거절: {}", e.getMessage());
            throw e;
        }
    }
}
