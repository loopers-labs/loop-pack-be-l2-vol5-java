package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 누락 복구의 운영 진입점 (W7 design.md 4-4절). Outbox 없이 확정된 주문을 <b>명시한 대상만</b> Outbox에 채워 단일 relay가 보내게 한다.
 *
 * <ul>
 *   <li>대상 목록 밖의 주문은 보지 않는다. 이전 주문 전체(4주차 이전 POINT 확정 등)를 자동으로 소급하지 않는다.</li>
 *   <li>주문마다 별도 트랜잭션이다. 한 대상이 거부돼도 다른 대상은 처리하고, 거부한 대상은 이유와 함께 결과에 남긴다(그 대상의 행은 쓰지 않는다).</li>
 *   <li>같은 대상을 다시 복구해도 행이 늘지 않는다(이미 있으면 같은 사실인지만 확인한다).</li>
 *   <li>공개 API로 노출하지 않는다. 로컬의 운영 도구·테스트가 직접 부르고, 보내는 것은 relay({@link OrderOutboxRelay#runOnce()})다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderOutboxBackfill {

    public enum Result { ADDED, ALREADY_PRESENT, REJECTED }

    /** 대상 한 건의 결과. 거부했으면 {@code reason}에 이유가 있다. */
    public record Outcome(Long orderId, String eventId, Result result, String reason) {
    }

    private final OrderOutboxService orderOutboxService;
    private final Clock clock;

    public OrderOutboxBackfill(OrderOutboxService orderOutboxService, Clock clock) {
        this.orderOutboxService = orderOutboxService;
        this.clock = clock;
    }

    public List<Outcome> backfill(List<Long> orderIds) {
        Instant recordedAt = clock.instant();
        List<Outcome> outcomes = new ArrayList<>();
        for (Long orderId : orderIds) {
            outcomes.add(backfillOne(orderId, recordedAt));
        }
        return outcomes;
    }

    private Outcome backfillOne(Long orderId, Instant recordedAt) {
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        try {
            Result result = switch (orderOutboxService.backfill(orderId, recordedAt)) {
                case ADDED -> Result.ADDED;
                case ALREADY_PRESENT -> Result.ALREADY_PRESENT;
            };
            log.info("Outbox 누락 복구: orderId={}, eventId={}, result={}", orderId, eventId, result);
            return new Outcome(orderId, eventId, result, null);
        } catch (RuntimeException e) {
            // 원본 사실 부족·기존 행과의 충돌·저장 실패. 추측해 채우지 않고 운영자가 원인을 보도록 남긴다
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Outbox 누락 복구 거부(행을 쓰지 않음): orderId={}, eventId={}, reason={}", orderId, eventId, reason);
            return new Outcome(orderId, eventId, Result.REJECTED, reason);
        }
    }
}
