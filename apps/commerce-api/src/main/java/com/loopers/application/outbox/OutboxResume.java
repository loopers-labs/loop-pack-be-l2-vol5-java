package com.loopers.application.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * BLOCKED 행을 다시 보내는 진입점(docs/week7/decisions.md D-3, D-15, INV-705). HTTP API로 열지 않는다 — 테스트와 로컬에서만
 * 빈 메서드로 부른다(과제 조건: 공개 관리자 API 금지).
 *
 * <p>같은 행을 PENDING으로 되돌리고(next_attempt_at = 지금) 그 행 하나로 relay 회차를 돈다. eventId·payload는 그대로다 —
 * 새 eventId로 보내면 consumer가 다른 이벤트로 본다. 시간을 무시하고 바로 보내는 문은 만들지 않는다 — relay와 같은 조건부
 * 집기를 지나므로 보내는 중인 행은 집지 않는다(D-11, INV-704). relay가 돌고 있으면 그 회차는 건너뛰고, 행은 다음 회차가 집는다.
 */
@RequiredArgsConstructor
@Component
public class OutboxResume {

    private final OutboxDeliveryLedger ledger;
    private final OutboxRelay relay;
    private final Clock clock;

    public OutboxRelayRound resume(String eventId) {
        ledger.resume(eventId, ZonedDateTime.now(clock));
        return relay.runOnceFor(eventId);
    }
}
