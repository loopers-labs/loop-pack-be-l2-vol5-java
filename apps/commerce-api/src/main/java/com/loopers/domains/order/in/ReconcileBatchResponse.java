package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.order.app.ReconcileBatchResult;
import java.util.List;

// 주문별 전/후 상태·판정·사유는 비어 있는 것 자체가 결과라 null도 그대로 내보낸다(전역 설정은 null 생략).
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReconcileBatchResponse(int requested, int processed, String stopReason, List<Item> results) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(long orderId, String beforeStatus, String afterStatus, String beforeProviderStatus,
        String afterProviderStatus, String assessment, String outcome, String reason) { }

    static ReconcileBatchResponse from(ReconcileBatchResult result) {
        List<Item> items = result.results().stream()
            .map(item -> new Item(item.orderId(), item.beforeStatus(), item.afterStatus(), item.beforeProviderStatus(),
                item.afterProviderStatus(), name(item.assessment()), name(item.outcome()), item.reason()))
            .toList();
        return new ReconcileBatchResponse(result.requested(), result.processed(), name(result.stopReason()), items);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
