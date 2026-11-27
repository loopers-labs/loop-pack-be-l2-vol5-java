package com.loopers.domain.payment;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 아직 종결되지 않은 결제 시도의 상태별 요약. 시도가 없는 상태는 건수·대기시간 모두 0 으로 본다. */
public final class UnresolvedPayments {

    /** 미종결 상태. 지표는 이 상태들을 항상 내보내 해소된 뒤에도 0 으로 남는다. */
    public static final List<PaymentAttemptStatus> STATUSES = Arrays.stream(PaymentAttemptStatus.values())
        .filter(status -> !status.isTerminated())
        .toList();

    private final Map<PaymentAttemptStatus, UnresolvedPaymentGroup> groups;

    public UnresolvedPayments(List<UnresolvedPaymentGroup> groups) {
        this.groups = groups.stream().collect(Collectors.toUnmodifiableMap(UnresolvedPaymentGroup::status, Function.identity()));
    }

    public long countOf(PaymentAttemptStatus status) {
        return group(status).map(UnresolvedPaymentGroup::count).orElse(0L);
    }

    /** now 는 생성 시각을 기록한 시계와 같은 시계의 현재 시각이어야 한다. */
    public long oldestAgeSeconds(PaymentAttemptStatus status, ZonedDateTime now) {
        return group(status)
            .map(group -> Math.max(0, Duration.between(group.oldestCreatedAt(), now).toSeconds()))
            .orElse(0L);
    }

    private Optional<UnresolvedPaymentGroup> group(PaymentAttemptStatus status) {
        return Optional.ofNullable(groups.get(status));
    }
}
