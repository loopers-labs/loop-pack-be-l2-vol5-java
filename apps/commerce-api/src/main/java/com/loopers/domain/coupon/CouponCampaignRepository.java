package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponCampaignRepository {

    // 준비 단계에서 발급 전 캠페인만 새로 저장한다. 발급 수 변경은 조건부 갱신을 사용한다.
    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long campaignId);

    List<CouponCampaign> findAllByName(String name);

    // 쿠폰 배정까지 포함하는 발급 트랜잭션 안에서 호출한다.
    int incrementIssuedCountIfIssuable(Long campaignId, Instant issueNow);

    // 로컬 seed의 명시적 reset 전용이다. 쿠폰 할당 초기화와 같은 트랜잭션에서 호출한다.
    int resetSeedState(Long campaignId, Instant startAt, Instant endAt, Instant resetNow);
}
