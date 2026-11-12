package com.loopers.coupon.adapter.in.web.dto;

import com.loopers.coupon.application.port.in.CouponCampaignAdminInfo;
import com.loopers.coupon.application.port.in.CouponCampaignCommandUseCase.CreateCampaignCommand;
import com.loopers.coupon.domain.CouponDraft;
import com.loopers.coupon.domain.CouponScope;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.ValidityType;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

public class CouponCampaignAdminDto {

    public record CreateRequest(
        String name,
        Integer totalQuantity,
        Integer queueCapacity,
        ZonedDateTime issueStartAt,
        ZonedDateTime issueEndAt,
        List<CouponRequest> coupons
    ) {
        public CreateCampaignCommand toCommand() {
            List<CouponDraft> drafts = coupons == null ? List.of() : coupons.stream().map(CouponRequest::toDraft).toList();
            return new CreateCampaignCommand(name, required(totalQuantity, "totalQuantity"), queueCapacity, issueStartAt, issueEndAt, drafts);
        }
    }

    public record CouponRequest(
        String name,
        DiscountType discountType,
        Long discountValue,
        Long maxDiscountAmount,
        Long minOrderAmount,
        CouponScope scope,
        Set<Long> applicableProductIds,
        ValidityType validityType,
        ZonedDateTime validFrom,
        ZonedDateTime validUntil,
        Integer validDays,
        Boolean stackable
    ) {
        CouponDraft toDraft() {
            return new CouponDraft(
                name,
                discountType,
                required(discountValue, "discountValue"),
                maxDiscountAmount,
                minOrderAmount == null ? 0 : minOrderAmount,
                scope,
                applicableProductIds,
                validityType,
                validFrom,
                validUntil,
                validDays,
                Boolean.TRUE.equals(stackable)
            );
        }
    }

    public record CampaignResponse(
        Long id,
        String name,
        int totalQuantity,
        int issuedCount,
        int queueCapacity,
        ZonedDateTime issueStartAt,
        ZonedDateTime issueEndAt,
        List<CouponResponse> coupons
    ) {
        public static CampaignResponse from(CouponCampaignAdminInfo info) {
            return new CampaignResponse(
                info.id(), info.name(), info.totalQuantity(), info.issuedCount(), info.queueCapacity(),
                info.issueStartAt(), info.issueEndAt(),
                info.coupons().stream().map(CouponResponse::from).toList()
            );
        }
    }

    public record CouponResponse(
        Long id,
        String name,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount,
        CouponScope scope,
        Set<Long> applicableProductIds,
        ValidityType validityType,
        ZonedDateTime validFrom,
        ZonedDateTime validUntil,
        Integer validDays,
        boolean stackable
    ) {
        static CouponResponse from(CouponCampaignAdminInfo.CouponInfo info) {
            return new CouponResponse(
                info.id(), info.name(), info.discountType(), info.discountValue(), info.maxDiscountAmount(),
                info.minOrderAmount(), info.scope(), info.applicableProductIds(), info.validityType(),
                info.validFrom(), info.validUntil(), info.validDays(), info.stackable()
            );
        }
    }

    public record ReplayResponse(int replayed) {}

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "필수 필드 '" + field + "'이(가) 누락되었습니다.");
        }
        return value;
    }
}
