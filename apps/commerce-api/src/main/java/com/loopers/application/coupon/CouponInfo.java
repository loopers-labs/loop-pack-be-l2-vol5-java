package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponIssuanceModel;
import com.loopers.domain.coupon.CouponQueuePort;

import java.time.ZonedDateTime;

public class CouponInfo {

    public record QueueEntry(long campaignId, long userId, int position) {
        public static QueueEntry of(long campaignId, long userId, int position) {
            return new QueueEntry(campaignId, userId, position);
        }
    }

    public record QueueStatus(String status, int position) {
        public static QueueStatus from(CouponQueuePort.QueueStatusResult result) {
            return new QueueStatus(result.status().name(), result.position());
        }
    }

    public record IssuedCoupon(long issuanceId, long campaignId, long userId, ZonedDateTime issuedAt) {
        public static IssuedCoupon from(CouponIssuanceModel model) {
            return new IssuedCoupon(model.getId(), model.getCampaignId(), model.getUserId(), model.getIssuedAt());
        }
    }

    public record CampaignDetail(
        long campaignId,
        String name,
        int totalQuota,
        int issuedCount,
        ZonedDateTime startAt,
        ZonedDateTime endAt
    ) {
        public static CampaignDetail from(CouponCampaignModel model) {
            return new CampaignDetail(
                model.getId(), model.getName(),
                model.getTotalQuota(), model.getIssuedCount(),
                model.getStartAt(), model.getEndAt()
            );
        }
    }

    public record AdmissionStatus(long campaignId, boolean isOpen) {}
}
