package com.loopers.couponissue.application.port.in;

import com.loopers.couponissue.domain.CouponIssueMessage;

public interface CouponIssueUseCase {

    Result issue(CouponIssueMessage message);

    enum Result {
        /** 쿠폰팩을 발급하고 발급 수를 올렸다 */
        ISSUED,
        /** 이미 발급된 사용자 — 아무것도 바꾸지 않았다 (재전달·rebalance) */
        ALREADY_ISSUED,
        /** 발급 수가 당첨자 수에 닿았다 — 아무것도 바꾸지 않았다 (Redis 일부 유실로 초과 수락된 경우, D-2 한계) */
        SOLD_OUT
    }
}
