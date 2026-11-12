package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.CouponInfo;

import java.time.ZonedDateTime;

public class CouponAdminV1Dto {

    public record CreateCampaignRequest(String name, int totalQuota, ZonedDateTime startAt, ZonedDateTime endAt) {}

    public record CampaignResponse(
        long campaignId, String name, int totalQuota, int issuedCount,
        ZonedDateTime startAt, ZonedDateTime endAt
    ) {
        public static CampaignResponse from(CouponInfo.CampaignDetail detail) {
            return new CampaignResponse(
                detail.campaignId(), detail.name(),
                detail.totalQuota(), detail.issuedCount(),
                detail.startAt(), detail.endAt()
            );
        }
    }

    public record AdmissionResponse(long campaignId, boolean isOpen) {
        public static AdmissionResponse from(CouponInfo.AdmissionStatus status) {
            return new AdmissionResponse(status.campaignId(), status.isOpen());
        }
    }
}
