package com.loopers.domain.coupon.gateway;

import com.loopers.domain.coupon.vo.PendingConfirm;

public interface CouponIssueRequestResender {

    /** 같은 key · 같은 순번으로 다시 보내 broker 확인까지 기다린다. false 는 실패와 "기록됐는지 모름"을 모두 포함한다 (8주차 D-134) */
    boolean resend(PendingConfirm pending);
}
