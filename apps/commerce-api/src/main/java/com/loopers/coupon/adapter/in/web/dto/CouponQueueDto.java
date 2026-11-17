package com.loopers.coupon.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.coupon.application.port.in.CouponIssueUseCase.IssueOutcome;
import com.loopers.coupon.application.port.in.CouponQueueUseCase.QueueStatusInfo;

import java.util.List;

public class CouponQueueDto {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueueResponse(String status, Long ahead, Long pollAfterMs) {
        public static QueueResponse from(QueueStatusInfo info) {
            return new QueueResponse(info.status().name(), info.ahead(), info.pollAfterMs());
        }
    }

    public record IssueResponse(String status, List<Long> couponIds) {
        public static IssueResponse from(IssueOutcome outcome) {
            return new IssueResponse(outcome.status().name(), outcome.couponIds());
        }
    }
}
