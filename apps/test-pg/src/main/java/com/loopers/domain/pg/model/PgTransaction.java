package com.loopers.domain.pg.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 카드 정보는 받지도 보관하지도 않는다. */
public record PgTransaction(String transactionKey, String orderId, long amount, String callbackUrl,
                            PgStatus status, PgScenario scenario, Instant createdAt, Instant dueAt) {
    public PgTransaction {
        if (transactionKey == null || transactionKey.isBlank() || transactionKey.length() > 64) {
            throw new IllegalArgumentException("거래 키가 올바르지 않습니다.");
        }
        if (orderId == null || orderId.isBlank() || orderId.length() < 6 || orderId.length() > 100 || amount < 0) {
            throw new IllegalArgumentException("주문 ID 또는 금액이 올바르지 않습니다.");
        }
        if (callbackUrl == null || callbackUrl.isBlank() || callbackUrl.length() > 2048) {
            throw new IllegalArgumentException("callback 주소가 필요합니다.");
        }
        Objects.requireNonNull(status, "거래 상태가 필요합니다.");
        Objects.requireNonNull(scenario, "실효 조건이 필요합니다.");
        Objects.requireNonNull(createdAt, "생성 시각이 필요합니다.");
        Instant expectedDue = scenario.finalStatus() == PgStatus.PENDING ? null
                : createdAt.plusMillis(scenario.processingDelayMs());
        if (scenario.rejectBeforeCreate() || !Objects.equals(expectedDue, dueAt)
                || (status != PgStatus.PENDING && status != scenario.finalStatus())) {
            throw new IllegalArgumentException("거래 상태와 예정 조건이 일치하지 않습니다.");
        }
    }

    public static PgTransaction create(String orderId, long amount, String callbackUrl, PgScenario scenario, Instant now) {
        Instant createdAt = Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);
        Instant dueAt = scenario.finalStatus() == PgStatus.PENDING ? null : createdAt.plusMillis(scenario.processingDelayMs());
        return new PgTransaction("TEST-TX-" + UUID.randomUUID(), orderId, amount, callbackUrl,
                PgStatus.PENDING, scenario, createdAt, dueAt);
    }

    public PgTransaction completeAt(Instant now) {
        if (status != PgStatus.PENDING || dueAt == null || dueAt.isAfter(now)) {
            throw new IllegalStateException("완료 기한이 된 처리 중 거래만 종결할 수 있습니다.");
        }
        return new PgTransaction(transactionKey, orderId, amount, callbackUrl,
                scenario.finalStatus(), scenario, createdAt, dueAt);
    }
}
