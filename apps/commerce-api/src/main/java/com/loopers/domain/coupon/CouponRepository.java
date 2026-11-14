package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Optional<Coupon> findById(Long couponId);

    // 발급 트랜잭션 안에서 새로 발급한 쿠폰을 저장한다. 같은 캠페인·사용자 쿠폰이 있으면 중복 예외로 구분한다.
    Coupon insert(Coupon issued);

    // 캠페인 reset으로 잠금을 얻은 로컬 seed 트랜잭션에서만 사용한다.
    int deleteByCampaignId(Long campaignId);

    Optional<Coupon> findIssuedByCampaignIdAndUserId(Long campaignId, Long userId);

    List<Coupon> findAllIssuedByUserId(Long userId);

    long countIssuedByCampaignId(Long campaignId);
}
