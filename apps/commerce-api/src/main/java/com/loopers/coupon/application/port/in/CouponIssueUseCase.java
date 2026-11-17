package com.loopers.coupon.application.port.in;

import java.util.List;

/**
 * 발급 (POST /issues, D-7). 입장한 사람만 Redis 판정을 받고, 당첨되면 기록을 남긴 뒤 202다.
 */
public interface CouponIssueUseCase {

    IssueOutcome issue(Long campaignId, Long userId);

    /**
     * @param couponIds ISSUED일 때 발급된 사용자 쿠폰 id들(쿠폰팩), ACCEPTED면 빈 목록
     */
    record IssueOutcome(Status status, List<Long> couponIds) {

        public static IssueOutcome accepted() {
            return new IssueOutcome(Status.ACCEPTED, List.of());
        }

        public static IssueOutcome issued(List<Long> couponIds) {
            return new IssueOutcome(Status.ISSUED, couponIds);
        }

        public enum Status { ACCEPTED, ISSUED }
    }
}
