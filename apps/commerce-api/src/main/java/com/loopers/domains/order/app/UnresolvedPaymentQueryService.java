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
    // 환불 확인 대상 필터(10주차 5b 계획 5절, PL P7). 미확인 목록이라 끝나지 않은 돈의 상태만, 담당은 사람 담당 중만 고른다.
    // 원천이 달라 한 페이지로 섞을 수 없으므로 결제 대기 상태 필터와 함께 받지 않는다.
    private static final Set<String> OPEN_MONEY_STATUSES = Set.of("CHECKING", "CHARGED", "REFUND_REQUESTED", "REFUND_UNKNOWN");
    private static final String PERSON_OWNER = "PERSON";

    private final UnresolvedPaymentFinder unresolvedPaymentFinder;
    private final UnresolvedRefundCaseFinder unresolvedRefundCaseFinder;
    private final Clock clock;

    public UnresolvedPaymentQueryService(UnresolvedPaymentFinder unresolvedPaymentFinder,
        UnresolvedRefundCaseFinder unresolvedRefundCaseFinder, Clock clock) {
        this.unresolvedPaymentFinder = unresolvedPaymentFinder;
        this.unresolvedRefundCaseFinder = unresolvedRefundCaseFinder;
        this.clock = clock;
    }

    public List<UnresolvedItem> list(List<String> statuses, String refundOwner, List<String> refundMoneyStatuses,
        long minAgeSeconds, int page, int size) {
        if (minAgeSeconds < 0) {
            throw new InvalidValueException("경과 시간 기준은 0 이상이어야 합니다.");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(minAgeSeconds);
        long offset = (long) page * size;
        Set<String> moneyStatuses = refundMoneyStatuses == null ? Set.of() : new LinkedHashSet<>(refundMoneyStatuses);
        if (refundOwner != null || !moneyStatuses.isEmpty()) {
            return refundCases(statuses, refundOwner, moneyStatuses, cutoff, offset, size, now);
        }
        if (statuses == null || statuses.isEmpty()) {
            return payments(UNRESOLVED_STATUSES, cutoff, offset, size, now);
        }
        Set<String> requested = new LinkedHashSet<>(statuses);
        if (!UNRESOLVED_STATUSES.containsAll(requested)) {
            throw new InvalidValueException("미확인 결제 상태는 SEND_STARTED·PENDING·UNKNOWN·NEEDS_REVIEW만 지정할 수 있습니다.");
        }
        return payments(requested, cutoff, offset, size, now);
    }

    private List<UnresolvedItem> refundCases(List<String> statuses, String refundOwner, Set<String> moneyStatuses,
        Instant cutoff, long offset, int size, Instant now) {
        if (statuses != null && !statuses.isEmpty()) {
            throw new InvalidValueException("결제 대기 상태 필터와 환불 확인 대상 필터는 함께 지정할 수 없습니다.");
        }
        if (refundOwner != null && !PERSON_OWNER.equals(refundOwner)) {
            throw new InvalidValueException("환불 확인 대상 담당 필터는 PERSON만 지정할 수 있습니다.");
        }
        if (!OPEN_MONEY_STATUSES.containsAll(moneyStatuses)) {
            throw new InvalidValueException("환불 확인 대상 돈의 상태는 CHECKING·CHARGED·REFUND_REQUESTED·REFUND_UNKNOWN만 "
                + "지정할 수 있습니다.");
        }
        return unresolvedRefundCaseFinder.find(refundOwner != null, moneyStatuses, cutoff, offset, size).stream()
            .<UnresolvedItem>map(row -> refundCaseView(row, now))
            .toList();
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
        return new UnresolvedRefundCaseView(row.orderId(), row.providerOrderId(), row.providerId(), row.amount(),
            row.moneyStatus(), row.refundActor(), row.owner(), row.ownerReason(), row.ownerChangedAt(), row.ownerClosedAt(),
            row.priorProviderStatus(), row.hasTransactionKey(), row.openedAt(),
            Duration.between(row.openedAt(), now).getSeconds(), row.handoffAt(), row.promiseAt(), row.checkAttempts(),
            row.nextCheckAt(), row.refundRequestedAt(), row.concludedAt());
    }
}
