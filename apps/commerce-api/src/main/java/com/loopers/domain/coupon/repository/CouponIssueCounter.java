package com.loopers.domain.coupon.repository;

import java.util.OptionalLong;

/**
 * 캠페인별 누적 발급 집계의 저장 계약. 저장 기술은 구현체가 결정한다.
 * DB 발급 결과를 원본으로 사용하며, 이 집계만으로 발급 총량이나 사용자 중복을 판단하지 않는다.
 */
public interface CouponIssueCounter {
    /**
     * DB commit이 완료된 발급 결과를 준비된 집계에 기록한다.
     * 동일한 campaignId/couponId의 재호출은 추가로 증가시키지 않으며 중복 확인과 반영은 원자적이어야 한다.
     * 새로 반영했으면 true, 이미 반영했으면 false다. 집계 미준비/접근 실패/결과 불명은 예외로 구분한다.
     */
    boolean recordIssued(long campaignId, long couponId);

    /**
     * 준비된 누적 발급 수를 조회한다. 발급이 없음을 확인한 집계는 값 0을 반환한다.
     * 미준비/유실된 집계는 empty이며 0과 다르다. 접근 실패는 empty나 0으로 숨기지 않고 예외로 전달한다.
     */
    OptionalLong findIssuedCount(long campaignId);
}
