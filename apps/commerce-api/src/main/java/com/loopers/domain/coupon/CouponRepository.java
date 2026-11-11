package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    // 준비 단계에서 새 미발급 재고만 저장한다. 발급은 조건부 배정을 사용한다.
    List<Coupon> saveStock(List<Coupon> stock);

    Optional<Coupon> findById(Long couponId);

    // 같은 캠페인의 카운터 갱신으로 잠금을 얻은 발급 트랜잭션 안에서 조회한다.
    Optional<Long> findFirstUnissuedId(Long campaignId);

    Optional<Coupon> findIssuedByCampaignIdAndUserId(Long campaignId, Long userId);

    List<Coupon> findAllIssuedByUserId(Long userId);

    long countByCampaignId(Long campaignId);

    long countIssuedByCampaignId(Long campaignId);

    int assignIfUnissued(Long couponId, Long campaignId, Long userId, Instant issueNow);

    // 캠페인 reset으로 잠금을 얻은 로컬 seed 트랜잭션에서만 사용한다.
    int resetSeedAssignments(Long campaignId, Instant resetNow);
}
