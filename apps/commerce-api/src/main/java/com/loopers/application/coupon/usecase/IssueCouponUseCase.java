package com.loopers.application.coupon.usecase;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.result.CouponIssueResult;

/** 내부 DB 발급 경로. 외부 노출과 로컬 수용 제어는 후속 단계에서 연결한다. */
public interface IssueCouponUseCase {
    CouponIssueResult execute(CouponIssueCommand command);
}
