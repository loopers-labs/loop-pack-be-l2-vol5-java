package com.loopers.interfaces.api.promotion;

import com.loopers.application.promotion.PromotionCommand;
import com.loopers.application.promotion.PromotionInfo;
import com.loopers.application.promotion.query.CouponView;

import java.time.ZonedDateTime;
import java.util.Map;

public class PromotionV1Dto {

    /** 관리자 프로모션 생성 요청. 검증은 Model·Service (INVALID_PROMOTION). */
    public record CreateRequest(
        String name,
        String type,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        CouponIssueRequest couponIssue
    ) {
        public PromotionCommand toCommand() {
            PromotionCommand.CouponIssue setting = couponIssue == null ? null
                : new PromotionCommand.CouponIssue(couponIssue.totalQuantity(), couponIssue.perUserLimit(), couponIssue.couponSpec());
            return new PromotionCommand(name, type, startsAt, endsAt, setting);
        }
    }

    public record CouponIssueRequest(Integer totalQuantity, Integer perUserLimit, Map<String, Object> couponSpec) {
    }

    public record PromotionResponse(
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
        public static PromotionResponse from(PromotionInfo.Created info) {
            return new PromotionResponse(info.promotionId(), info.name(), info.type(), info.status(), info.startsAt(), info.endsAt(),
                info.totalQuantity(), info.perUserLimit(), info.couponSpec());
        }
    }

    public record IssuedCouponResponse(Long couponId, Long promotionId, ZonedDateTime issuedAt) {
        public static IssuedCouponResponse from(PromotionInfo.Issued info) {
            return new IssuedCouponResponse(info.couponId(), info.promotionId(), info.issuedAt());
        }
    }

    public record MyCouponResponse(Long couponId, Long promotionId, String promotionName, Map<String, Object> couponSpec,
                                   ZonedDateTime issuedAt) {
        public static MyCouponResponse from(CouponView.MyCoupon view) {
            return new MyCouponResponse(view.couponId(), view.promotionId(), view.promotionName(), view.couponSpec(), view.issuedAt());
        }
    }
}
