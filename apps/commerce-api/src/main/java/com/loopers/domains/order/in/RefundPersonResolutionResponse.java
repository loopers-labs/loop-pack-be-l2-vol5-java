package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.payment.app.RefundPersonResolutionService;
import java.time.Instant;

// 환불 주체가 없는 청구 없음도 칸을 null로 그대로 내보낸다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RefundPersonResolutionResponse(long orderId, String moneyStatus, String refundActor, String owner,
    Instant ownerClosedAt, String ownerClosedBy) {
    static RefundPersonResolutionResponse from(RefundPersonResolutionService.Resolution resolution) {
        return new RefundPersonResolutionResponse(resolution.orderId(), resolution.moneyStatus(), resolution.refundActor(),
            resolution.owner(), resolution.ownerClosedAt(), resolution.ownerClosedBy());
    }
}
