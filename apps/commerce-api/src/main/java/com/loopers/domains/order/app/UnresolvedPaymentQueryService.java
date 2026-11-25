package com.loopers.domains.order.app;

import com.loopers.common.error.InvalidValueException;
import com.loopers.domains.order.app.port.UnresolvedPaymentFinder;
import com.loopers.domains.payment.domain.PaymentRecord.ProviderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class UnresolvedPaymentQueryService {
    // 결제 대기 주문에 남을 수 있는 PG 결제 상태다. 종결 상태는 주문이 결제 대기가 아니므로 목록 대상이 아니다.
    private static final Set<String> UNRESOLVED_STATUSES = Stream.of(ProviderStatus.SEND_STARTED, ProviderStatus.PENDING,
        ProviderStatus.UNKNOWN, ProviderStatus.NEEDS_REVIEW).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final UnresolvedPaymentFinder unresolvedPaymentFinder;
    private final Clock clock;

    public UnresolvedPaymentQueryService(UnresolvedPaymentFinder unresolvedPaymentFinder, Clock clock) {
        this.unresolvedPaymentFinder = unresolvedPaymentFinder;
        this.clock = clock;
    }

    public List<UnresolvedPaymentView> list(List<String> providerStatuses, long minAgeSeconds, int page, int size) {
        if (minAgeSeconds < 0) {
            throw new InvalidValueException("경과 시간 기준은 0 이상이어야 합니다.");
        }
        Set<String> statuses = statuses(providerStatuses);
        Instant now = clock.instant();
        return unresolvedPaymentFinder.find(statuses, now.minusSeconds(minAgeSeconds), (long) page * size, size).stream()
            .map(row -> view(row, now))
            .toList();
    }

    private static Set<String> statuses(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return UNRESOLVED_STATUSES;
        }
        Set<String> statuses = new LinkedHashSet<>(requested);
        if (!UNRESOLVED_STATUSES.containsAll(statuses)) {
            throw new InvalidValueException("미확인 결제 상태는 SEND_STARTED·PENDING·UNKNOWN·NEEDS_REVIEW만 지정할 수 있습니다.");
        }
        return statuses;
    }

    private static UnresolvedPaymentView view(UnresolvedPaymentFinder.Row row, Instant now) {
        UnresolvedPaymentView.Anomaly anomaly = row.anomalyKind() == null ? null
            : new UnresolvedPaymentView.Anomaly(row.anomalyKind(), row.anomalySummary(), row.anomalyLastSeenAt());
        return new UnresolvedPaymentView(row.orderId(), row.providerOrderId(), row.providerId(), row.providerStatus(),
            row.startedAt(), Duration.between(row.startedAt(), now).getSeconds(), row.lookupAttempts(), row.nextLookupAt(),
            anomaly);
    }
}
