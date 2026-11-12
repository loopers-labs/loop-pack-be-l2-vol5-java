package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponInfo;

import java.time.ZonedDateTime;

public class CouponV1Dto {

    public record EnqueueResponse(long campaignId, long userId, int position) {
        public static EnqueueResponse from(CouponInfo.QueueEntry entry) {
            return new EnqueueResponse(entry.campaignId(), entry.userId(), entry.position());
        }
    }

    public record QueueStatusResponse(String status, int position) {
        public static QueueStatusResponse from(CouponInfo.QueueStatus status) {
            return new QueueStatusResponse(status.status(), status.position());
        }
    }

    public record IssueResponse(long issuanceId, long campaignId, long userId, ZonedDateTime issuedAt) {
        public static IssueResponse from(CouponInfo.IssuedCoupon coupon) {
            return new IssueResponse(coupon.issuanceId(), coupon.campaignId(), coupon.userId(), coupon.issuedAt());
        }
    }
}
