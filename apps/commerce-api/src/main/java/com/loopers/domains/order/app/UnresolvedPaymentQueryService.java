package com.loopers.domains.order.app;

import com.loopers.common.error.InvalidValueException;
import com.loopers.domains.order.app.port.UnresolvedPaymentFinder;
import com.loopers.domains.order.app.port.UnresolvedRefundCaseFinder;
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
    // 사람 조치가 필요한 환불 확인 대상 상태다(P7). 원천이 달라 한 페이지로 섞을 수 없으므로 결제 대기 상태와 함께 받지 않는다.
    // 5b 두 축 모델로 바뀌기 전 계약이다. 필터를 refundOwner·refundMoneyStatus로 바꾸는 작업은 다음 단계(계획 5절)다.
    private static final Set<String> REFUND_CASE_STATUSES = Set.of("HANDED_TO_HUMAN", "REFUND_UNKNOWN");

    private final UnresolvedPaymentFinder unresolvedPaymentFinder;
    private final UnresolvedRefundCaseFinder unresolvedRefundCaseFinder;
    private final Clock clock;

    public UnresolvedPaymentQueryService(UnresolvedPaymentFinder unresolvedPaymentFinder,
        UnresolvedRefundCaseFinder unresolvedRefundCaseFinder, Clock clock) {
        this.unresolvedPaymentFinder = unresolvedPaymentFinder;
        this.unresolvedRefundCaseFinder = unresolvedRefundCaseFinder;
        this.clock = clock;
    }

    public List<UnresolvedItem> list(List<String> statuses, long minAgeSeconds, int page, int size) {
        if (minAgeSeconds < 0) {
            throw new InvalidValueException("경과 시간 기준은 0 이상이어야 합니다.");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(minAgeSeconds);
        long offset = (long) page * size;
        if (statuses == null || statuses.isEmpty()) {
            return payments(UNRESOLVED_STATUSES, cutoff, offset, size, now);
        }
        Set<String> requested = new LinkedHashSet<>(statuses);
        if (REFUND_CASE_STATUSES.containsAll(requested)) {
            return unresolvedRefundCaseFinder.find(requested, cutoff, offset, size).stream()
                .<UnresolvedItem>map(row -> refundCaseView(row, now))
                .toList();
        }
        if (!UNRESOLVED_STATUSES.containsAll(requested)) {
            throw new InvalidValueException("미확인 결제 상태는 SEND_STARTED·PENDING·UNKNOWN·NEEDS_REVIEW 또는 "
                + "HANDED_TO_HUMAN·REFUND_UNKNOWN 중 한쪽만 지정할 수 있습니다.");
        }
        return payments(requested, cutoff, offset, size, now);
    }

    private List<UnresolvedItem> payments(Set<String> statuses, Instant cutoff, long offset, int size, Instant now) {
        return unresolvedPaymentFinder.find(statuses, cutoff, offset, size).stream()
            .<UnresolvedItem>map(row -> paymentView(row, now))
            .toList();
    }

    private static UnresolvedPaymentView paymentView(UnresolvedPaymentFinder.Row row, Instant now) {
        UnresolvedPaymentView.Anomaly anomaly = row.anomalyKind() == null ? null
            : new UnresolvedPaymentView.Anomaly(row.anomalyKind(), row.anomalySummary(), row.anomalyLastSeenAt());
        return new UnresolvedPaymentView(row.orderId(), row.providerOrderId(), row.providerId(), row.providerStatus(),
            row.startedAt(), Duration.between(row.startedAt(), now).getSeconds(), row.lookupAttempts(), row.nextLookupAt(),
            anomaly);
    }

    private static UnresolvedRefundCaseView refundCaseView(UnresolvedRefundCaseFinder.Row row, Instant now) {
        return new UnresolvedRefundCaseView(row.orderId(), row.providerOrderId(), row.providerId(), row.amount(), row.status(),
            row.priorProviderStatus(), row.hasTransactionKey(), row.reason(), row.openedAt(),
            Duration.between(row.openedAt(), now).getSeconds(), row.handoffAt(), row.promiseAt(), row.checkAttempts(),
            row.nextCheckAt(), row.refundRequestedAt(), row.concludedAt());
    }
}
