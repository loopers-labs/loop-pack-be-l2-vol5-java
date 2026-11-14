package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

/**
 * 대기 작업 가져오기 결과.
 */
public sealed interface CouponIssueQueueClaim {

    /** 대기 List 맨 앞의 작업을 처리 중으로 옮기고 실행 token을 기록했다. */
    record Claimed(CouponIssueRequest request) implements CouponIssueQueueClaim {
    }

    /** 가져올 대기 작업이 없다. */
    record Empty() implements CouponIssueQueueClaim {
    }
}
