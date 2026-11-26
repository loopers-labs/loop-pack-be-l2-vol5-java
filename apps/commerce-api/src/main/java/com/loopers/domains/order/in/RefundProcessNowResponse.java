package com.loopers.domains.order.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domains.payment.app.RefundTrackingService;
import java.time.Instant;

// 끝나지 않은 확인도 칸을 null로 그대로 내보낸다. 담당 종료 시각이 null이면 담당이 열려 있다.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RefundProcessNowResponse(long orderId, String moneyStatus, String refundActor, String owner,
    Instant ownerClosedAt, String ownerClosedBy, Instant lastCheckAt, String lastCheckOutcome, String lastCheckCause) {
    static RefundProcessNowResponse from(RefundTrackingService.ProcessNowResult result) {
        return new RefundProcessNowResponse(result.orderId(), result.moneyStatus(), result.refundActor(), result.owner(),
            result.ownerClosedAt(), result.ownerClosedBy(), result.lastCheckAt(), result.lastCheckOutcome(),
            result.lastCheckCause());
    }
}
