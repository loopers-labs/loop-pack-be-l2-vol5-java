package com.loopers.application.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * 단일 Outbox relay의 실행·재시도 설정 (W7 design.md 4절). broker 확인을 기다리는 시간은 발행기가 {@code order-outbox.relay.send-timeout}으로 따로 읽는다.
 *
 * @param schedulingEnabled 이 인스턴스에서 relay를 주기적으로 실행할지. 여러 인스턴스 중 한 곳에서만 켠다(분산 잠금이 없다)
 * @param fixedDelay        한 회차가 끝난 뒤 다음 회차까지 쉬는 시간
 * @param batchSize         한 회차에 고르는 최대 행 수
 * @param initialBackoff    첫 실패 뒤 다음 시도까지의 대기. 이후 실패마다 {@code backoffMultiplier}배로 늘어난다
 * @param maxBackoff        대기의 상한. 영구 오류도 이 간격보다 자주 다시 보내지 않는다
 */
@ConfigurationProperties("order-outbox.relay")
public record OrderOutboxRelayProperties(
    boolean schedulingEnabled,
    Duration fixedDelay,
    int batchSize,
    Duration initialBackoff,
    double backoffMultiplier,
    Duration maxBackoff
) {
    public OrderOutboxRelayProperties {
        Objects.requireNonNull(fixedDelay, "order-outbox.relay.fixed-delay가 없다");
        Objects.requireNonNull(initialBackoff, "order-outbox.relay.initial-backoff가 없다");
        Objects.requireNonNull(maxBackoff, "order-outbox.relay.max-backoff가 없다");
        if (batchSize < 1) {
            throw new IllegalArgumentException("order-outbox.relay.batch-size는 1 이상이어야 한다");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("order-outbox.relay.backoff-multiplier는 1 이상이어야 한다");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("order-outbox.relay.max-backoff는 initial-backoff 이상이어야 한다");
        }
    }

    /** {@code attempt}번째 시도가 실패한 뒤 기다릴 시간. 1번째 실패는 initialBackoff이고, 이후 배수로 늘다가 maxBackoff에서 멈춘다. */
    public Duration backoffAfter(int attempt) {
        double factor = Math.pow(backoffMultiplier, Math.max(0, attempt - 1));
        double millis = Math.min(initialBackoff.toMillis() * factor, maxBackoff.toMillis());
        return Duration.ofMillis((long) millis);
    }
}
