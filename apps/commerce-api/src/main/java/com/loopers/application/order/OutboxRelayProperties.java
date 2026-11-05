package com.loopers.application.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml outbox.relay.*. 주문 이벤트 Outbox relay (OrderEventRelay·OrderEventRelayJob).
 *
 * @param enabled         주기 실행을 켠다. 한 relay 만 돌도록 인스턴스 하나에서만 true 로 둔다 (프로세스 사이 소유권은 보장하지 않는다)
 * @param interval        한 회차가 끝난 뒤 다음 회차까지 (회차는 겹치지 않는다)
 * @param batchSize       한 회차에 고르는 최대 행 수. 고른 순서대로 한 건씩 보낸다
 * @param sendTimeout     broker 확인을 기다리는 최대 시간. producer delivery.timeout.ms 보다 길게 둬 대부분 producer 가 결론낸 뒤 판단한다.
 *                        시작한 시도가 결과를 저장하지 못하고 멈추면 이만큼 뒤에 다시 고른다
 * @param retryBackoff    첫 실패 뒤 재시도 간격. 연속 실패마다 두 배
 * @param maxRetryBackoff 재시도 간격 상한
 * @param maxFailures     연속 실패가 이 횟수에 닿으면 BLOCKED (예외·대기 초과 구분 없음)
 */
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(boolean enabled, Duration interval, int batchSize, Duration sendTimeout,
                                    Duration retryBackoff, Duration maxRetryBackoff, int maxFailures) {

    /** failures 번째 연속 실패 뒤의 재시도 간격. retryBackoff × 2^(failures-1), 상한 maxRetryBackoff. */
    public Duration retryDelayAfter(int failures) {
        Duration delay = retryBackoff;
        for (int i = 1; i < failures && delay.compareTo(maxRetryBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : delay;
    }
}
