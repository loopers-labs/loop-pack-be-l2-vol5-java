package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.order.app.UnresolvedPaymentView;
import java.time.Instant;

// 운영자가 "값 없음"과 "필드 없음"을 헷갈리지 않게 null도 그대로 내보낸다(전역 설정은 null 생략).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnresolvedPaymentResponse(long orderId, String providerOrderId, String providerId, String providerStatus,
    Instant startedAt, long elapsedSeconds, Integer lookupAttempts, Instant nextLookupAt, Anomaly latestAnomaly) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Anomaly(String kind, String summary, Instant lastSeenAt) { }

    static UnresolvedPaymentResponse from(UnresolvedPaymentView view) {
        UnresolvedPaymentView.Anomaly anomaly = view.latestAnomaly();
        return new UnresolvedPaymentResponse(view.orderId(), view.providerOrderId(), view.providerId(), view.providerStatus(),
            view.startedAt(), view.elapsedSeconds(), view.lookupAttempts(), view.nextLookupAt(),
            anomaly == null ? null : new Anomaly(anomaly.kind(), anomaly.summary(), anomaly.lastSeenAt()));
    }
}
