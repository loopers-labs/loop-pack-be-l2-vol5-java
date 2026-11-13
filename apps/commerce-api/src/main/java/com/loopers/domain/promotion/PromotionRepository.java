package com.loopers.domain.promotion;

import java.util.Optional;

public interface PromotionRepository {
    PromotionModel save(PromotionModel promotion);

    Optional<PromotionModel> find(Long id);

    CouponIssuePromotionModel saveCouponIssue(CouponIssuePromotionModel couponIssue);

    Optional<CouponIssuePromotionModel> findCouponIssue(Long promotionId);

    CouponIssueStockModel saveStock(CouponIssueStockModel stock);

    Optional<CouponIssueStockModel> findStock(Long promotionId);

    /** remaining > 0 일 때만 1 줄인다. 줄였으면 true, 남은 수량이 없으면 false. */
    boolean decreaseStock(Long promotionId);

    /** 바로 insert 한다. 같은 (userId, promotionId) 가 이미 있으면 empty. */
    Optional<IssuedCouponModel> insertIssuedCoupon(IssuedCouponModel issuedCoupon);

    /** 이 사용자가 이 프로모션에서 이미 발급받았는지. */
    boolean existsIssuedCoupon(Long userId, Long promotionId);
}
