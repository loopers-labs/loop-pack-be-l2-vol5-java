package com.loopers.domain.promotion;

import java.util.Optional;

public interface PromotionRepository {
    PromotionModel save(PromotionModel promotion);

    Optional<PromotionModel> find(Long id);

    CouponIssuePromotionModel saveCouponIssue(CouponIssuePromotionModel couponIssue);

    Optional<CouponIssuePromotionModel> findCouponIssue(Long promotionId);
}
