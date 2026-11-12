package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.domain.coupon.Coupon;

import java.time.Instant;
import java.util.List;

public class CouponV1Dto {

    public enum IssueStatus {
        PENDING, ISSUED
    }

    public record IssueResponse(int issueNumber, IssueStatus status, Long couponId, Long discountAmount,
                                Instant issuedAt) {
        public static IssueResponse from(CouponFacade.IssueResult result) {
            return switch (result) {
                case CouponFacade.IssueResult.Accepted(int issueNumber) ->
                    new IssueResponse(issueNumber, IssueStatus.PENDING, null, null, null);
                case CouponFacade.IssueResult.Pending(int issueNumber) ->
                    new IssueResponse(issueNumber, IssueStatus.PENDING, null, null, null);
                case CouponFacade.IssueResult.Issued(int issueNumber, Coupon coupon) ->
                    new IssueResponse(issueNumber, IssueStatus.ISSUED, coupon.id(),
                        coupon.discountAmount().value(), coupon.issuedAt());
            };
        }
    }

    public record CouponResponse(Long couponId, Long campaignId, long discountAmount, Instant issuedAt) {
        public static CouponResponse from(Coupon coupon) {
            return new CouponResponse(coupon.id(), coupon.campaignId(), coupon.discountAmount().value(),
                coupon.issuedAt());
        }

        public static List<CouponResponse> listOf(List<Coupon> coupons) {
            return coupons.stream().map(CouponResponse::from).toList();
        }
    }
}
