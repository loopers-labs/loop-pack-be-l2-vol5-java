package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Outbox 재시도 간격과 회차 시도 한도의 순수 계산(설계 W7.3-3). Clock·DB·Kafka 를 쓰지 않는다.
 * 실패 번호 n 의 지연은 min(initial × 2^(n-1), max) 이며, 두 배 계산은 상한에 닿으면 멈춰 overflow 하지 않는다.
 * 간격 상한과 회차 한도는 다른 축이다. 한도는 영구 오류 판정이 아니라 자동 처리 예산이다.
 */
public final class OrderOutboxRetryPolicy {

    /** DATETIME(6) 의 상한 연도. 다음 시각이 이를 넘으면 저장할 수 없다. */
    private static final int MAX_STORABLE_YEAR = 9999;

    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final int maxCycleAttempts;

    public OrderOutboxRetryPolicy(Duration initialRetryDelay, Duration maxRetryDelay, int maxCycleAttempts) {
        if (initialRetryDelay == null || maxRetryDelay == null || initialRetryDelay.isNegative() || initialRetryDelay.isZero()
            || maxRetryDelay.compareTo(initialRetryDelay) < 0 || maxCycleAttempts < 1) {
            throw new IllegalArgumentException("Outbox 재시도 설정은 양수 지연·상한≥초기·회차 한도≥1 이어야 합니다.");
        }
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.maxCycleAttempts = maxCycleAttempts;
    }

    public int maxCycleAttempts() {
        return maxCycleAttempts;
    }

    /** 회차 안 n 번째 시도·실패의 지연. n 은 1 이상이다. */
    public Duration delayFor(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다.");
        }
        Duration delay = initialRetryDelay;
        Duration half = maxRetryDelay.dividedBy(2);
        for (int i = 1; i < attemptNumber && delay.compareTo(maxRetryDelay) < 0; i++) {
            delay = delay.compareTo(half) > 0 ? maxRetryDelay : delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }

    /** now 에 지연을 더한 다음 시도 시각. 표현·저장 범위를 넘으면 INTERNAL_ERROR 다. */
    public ZonedDateTime nextAttemptAt(ZonedDateTime now, int attemptNumber) {
        Duration delay = delayFor(attemptNumber);
        try {
            ZonedDateTime next = now.plus(delay);
            if (next.getYear() > MAX_STORABLE_YEAR) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "다음 시도 시각이 저장 범위를 넘습니다.");
            }
            return next;
        } catch (DateTimeException | ArithmeticException outOfRange) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "다음 시도 시각이 표현 범위를 넘습니다.");
        }
    }
}
