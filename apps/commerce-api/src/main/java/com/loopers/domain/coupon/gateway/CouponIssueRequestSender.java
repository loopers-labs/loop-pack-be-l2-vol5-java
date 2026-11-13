package com.loopers.domain.coupon.gateway;

import com.loopers.domain.coupon.vo.IssueRequested;

public interface CouponIssueRequestSender {

    /**
     * broker 확인까지 기다린다. false 는 실패와 "기록됐는지 모름"을 모두 포함하므로,
     * 호출자는 접수를 확정하지 않고 재전송 · 대사에 맡긴다 (8주차 D-133, D-134)
     */
    boolean send(IssueRequested request);
}
