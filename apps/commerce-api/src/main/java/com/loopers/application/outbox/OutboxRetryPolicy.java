package com.loopers.application.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 보내기에 실패한 Outbox 행을 언제 다시 집을지, 언제 BLOCKED로 뺄지 정한다(docs/week7/decisions.md D-3).
 *
 * - 실패 원인과 상관없이 같은 백오프다. 예외 종류로 나누면 잠깐 지나갈 실패를 영구로 잘못 분류할 수 있고, 타임아웃처럼 broker가
 *   받았는지조차 모르는 실패도 있다.
 * - 백오프: n번째 시도가 실패하면 initial × 2^(n-1)(1초, 2초, 4초 …), max(5분)에서 멈춘다.
 * - BLOCKED: created_at(BaseEntity, D-16)부터 blockAfter(24시간)가 지난 뒤의 실패는 다시 잡지 않고 뺀다. 행은 지우지 않는다(INV-705).
 * - 시각을 스스로 읽지 않는다. relay가 주입한 Clock의 "지금"을 넘긴다(D-11).
 */
@Component
public class OutboxRetryPolicy {

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration blockAfter;

    public OutboxRetryPolicy(
        @Value("${outbox.relay.backoff.initial:1s}") Duration initialBackoff,
        @Value("${outbox.relay.backoff.max:5m}") Duration maxBackoff,
        @Value("${outbox.relay.block-after:24h}") Duration blockAfter
    ) {
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("백오프는 0보다 크고 max ≥ initial이어야 합니다.");
        }
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.blockAfter = blockAfter;
    }

    /**
     * @param attemptCount 방금 실패한 시도가 몇 번째인지(집을 때 1 늘린 뒤의 attempt_count, 1부터)
     */
    public Duration backoffAfter(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount는 1부터입니다: " + attemptCount);
        }
        Duration backoff = initialBackoff;
        for (int i = 1; i < attemptCount; i++) {
            backoff = backoff.multipliedBy(2);
            if (backoff.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /** 지금이 created_at + blockAfter와 같거나 그 뒤면 BLOCKED로 뺀다. 그 전이면 백오프로 다시 시도한다. */
    public boolean shouldBlock(ZonedDateTime createdAt, ZonedDateTime now) {
        return !now.isBefore(createdAt.plus(blockAfter));
    }
}
