package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.vo.IssueEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 발급 TX 의 저장소. 공유 테이블을 엔티티 없이 다룬다 (8주차 D-172) */
public interface CouponIssuanceStore {

    List<Long> findOpenCampaignIds();

    /**
     * 쿠폰을 슬롯에 넣고 접수 기록을 ISSUED 로 바꾼다. 호출자의 TX 안에서 부른다.
     * 이미 그 사용자의 쿠폰이 있으면 새로 넣지 않고 그 쿠폰을 돌려준다(같은 항목의 재처리) (8주차 D-125, D-141)
     *
     * @return couponId
     */
    long issue(IssueEntry entry, Instant now);

    /**
     * 최종 실패를 기록한다. 그 전에 그 사용자의 쿠폰이 이미 있는지 새로 확인한다.
     * commit 결과를 모르는 오류였다면 실제로는 발급됐을 수 있다 (8주차 D-120)
     *
     * @return 이미 있는 쿠폰 id. 있으면 FAILED 로 기록하지 않는다
     */
    Optional<Long> markFailed(IssueEntry entry, Instant now);

    /**
     * issuedCount 를 쿠폰 수로 맞춘다. GREATEST 로 값이 뒤로 가지 않는다 (8주차 D-142)
     *
     * @return 발급 수가 한도에 닿았는가
     */
    boolean recountIssued(long campaignId);
}
