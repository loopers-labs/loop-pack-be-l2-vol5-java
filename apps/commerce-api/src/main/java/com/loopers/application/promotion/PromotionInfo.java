package com.loopers.application.promotion;

import com.loopers.domain.promotion.CouponIssuePromotionModel;
import com.loopers.domain.promotion.IssuedCouponModel;
import com.loopers.domain.promotion.PromotionModel;
import com.loopers.domain.promotion.PromotionService;

import java.time.ZonedDateTime;
import java.util.Map;

/** 프로모션 명령 결과. */
public final class PromotionInfo {
    private PromotionInfo() {
    }

    /** 생성된 쿠폰 발급 프로모션. */
    public record Created(
        Long promotionId,
        String name,
        String type,
        String status,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        Integer totalQuantity,
        Integer perUserLimit,
        Map<String, Object> couponSpec
    ) {
        public static Created from(PromotionService.CouponIssuePromotion created) {
            PromotionModel promotion = created.promotion();
            CouponIssuePromotionModel couponIssue = created.couponIssue();
            return new Created(promotion.getId(), promotion.getName(), promotion.getType().name(), promotion.getStatus().name(),
                promotion.getStartsAt(), promotion.getEndsAt(),
                couponIssue.getTotalQuantity(), couponIssue.getPerUserLimit(), couponIssue.getCouponSpec());
        }
    }

    /** 발급된 쿠폰. */
    public record Issued(Long couponId, Long promotionId, ZonedDateTime issuedAt) {
        public static Issued from(IssuedCouponModel coupon) {
            return new Issued(coupon.getId(), coupon.getPromotionId(), coupon.getIssuedAt());
        }
    }
}
