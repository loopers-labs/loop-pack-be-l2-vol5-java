package com.loopers.application.outbox;

import com.loopers.application.order.SentRecord;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * relay 한 행의 DB 기록을 맡는다 — 집기(TX-1)와 결과 기록(TX-2), 그리고 BLOCKED 재개(docs/week7/decisions.md D-2, D-11).
 *
 * - relay와 다른 빈인 이유: Spring {@code @Transactional}은 같은 클래스 안의 자기 호출에는 걸리지 않는다. TX-1·TX-2를 relay
 *   안에 두면 트랜잭션이 생기지 않는다(D-11 이유 1).
 * - 테스트 이음매이기도 하다: "broker 확인 뒤 PUBLISHED 저장 전 중단" 테스트는 이 빈을 감싼 래퍼로 {@link #markPublished}에서
 *   멈춘다. 이 호출은 broker 성공 뒤에만 오므로 broker 확인까지는 진짜로 끝난 상태다(D-11 이유 2).
 * - 각 메서드는 짧은 TX 하나다. send는 여기 없다 — broker를 기다리는 동안 DB 연결과 행 잠금을 붙잡지 않는다(D-2).
 * - 상태 전이 규칙(PENDING에서만 PUBLISHED·실패 기록·BLOCKED, BLOCKED에서만 재개)은 엔티티가 지킨다(D-12).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxDeliveryLedger {

    /** 실패 기록의 결과. relay가 회차 로그에 "이번 회차에 BLOCKED로 뺀 수"를 남긴다. */
    public enum FailureOutcome { RETRY_SCHEDULED, BLOCKED }

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRetryPolicy retryPolicy;

    /**
     * TX-1: 조건부 UPDATE로 집는다 — attempt_count + 1, next_attempt_at = leaseUntil(D-2 임대, D-4, INV-704).
     * 0줄이면 다른 회차가 집었거나 이미 보낸 것이니 빈 값을 돌려준다.
     */
    @Transactional
    public Optional<ClaimedOutbox> claim(Long id, ZonedDateTime now, ZonedDateTime leaseUntil) {
        if (outboxEventRepository.claim(id, now, leaseUntil) == 0) {
            return Optional.empty();
        }
        OutboxEvent event = load(id);
        return Optional.of(new ClaimedOutbox(event.getId(), event.getEventId(), event.getOrderId(), event.getMessageKey(),
            event.getPayload(), event.getAttemptCount(), event.getCreatedAt()));
    }

    /**
     * TX-2 성공: broker가 받았다고 응답한 뒤에만 부른다(INV-703). broker가 준 위치를 함께 받아 로그로 남긴다 — 재발행이 생기면
     * 같은 eventId가 어느 offset들에 실렸는지 추적하는 근거다(D-11).
     */
    @Transactional
    public void markPublished(Long id, SentRecord sent, ZonedDateTime now) {
        OutboxEvent event = load(id);
        event.markPublished(now);
        log.info("Outbox 발행 완료: eventId={}, orderId={}, attempt={}, topic={}, partition={}, offset={}",
            event.getEventId(), event.getOrderId(), event.getAttemptCount(), sent.topic(), sent.partition(), sent.offset());
    }

    /**
     * TX-2 실패: created_at부터 24시간이 지났으면 BLOCKED로 빼고, 아니면 PENDING을 유지한 채 next_attempt_at = now + 백오프로
     * 임대를 바꿔 적는다(D-2 "바로잡은 것", D-3).
     *
     * @param error 예외 종류와 짧은 메시지만. payload나 비밀값은 넣지 않는다 — last_error와 로그로 흘러간다.
     */
    @Transactional
    public FailureOutcome recordFailure(Long id, String error, ZonedDateTime now) {
        OutboxEvent event = load(id);
        if (retryPolicy.shouldBlock(event.getCreatedAt(), now)) {
            event.block(error);
            return FailureOutcome.BLOCKED;
        }
        event.recordFailure(error, now.plus(retryPolicy.backoffAfter(event.getAttemptCount())));
        return FailureOutcome.RETRY_SCHEDULED;
    }

    /**
     * BLOCKED인 같은 행을 PENDING으로 되돌리고 지금 바로 집을 수 있게 한다. eventId·payload는 그대로다(D-3, INV-705).
     * BLOCKED가 아니면 엔티티가 거절한다.
     */
    @Transactional
    public void resume(String eventId, ZonedDateTime now) {
        OutboxEvent event = outboxEventRepository.findByEventId(eventId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "Outbox 행을 찾을 수 없습니다. (eventId: " + eventId + ")"));
        event.resume(now);
        log.info("Outbox 재개: eventId={}, orderId={}, attempt={}", event.getEventId(), event.getOrderId(), event.getAttemptCount());
    }

    private OutboxEvent load(Long id) {
        return outboxEventRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "Outbox 행을 찾을 수 없습니다. (id: " + id + ")"));
    }
}
