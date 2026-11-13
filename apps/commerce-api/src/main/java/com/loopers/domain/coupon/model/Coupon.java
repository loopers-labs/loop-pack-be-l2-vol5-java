package com.loopers.domain.coupon.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 발급 시점의 불변 결과. 캠페인 종료·처리 permit 반환으로 삭제하거나 수정하지 않는다. */
public final class Coupon {
    private final Long id;
    private final long campaignId;
    private final long slotId;
    private final long userId;
    private final CouponBenefit benefit;
    private final Instant issuedAt;

    private Coupon(Long id, long campaignId, long slotId, long userId, CouponBenefit benefit, Instant issuedAt) {
        if (campaignId <= 0 || slotId <= 0 || benefit == null || issuedAt == null) {
            throw new IllegalArgumentException("발급 쿠폰의 캠페인·슬롯·혜택·발급 시각이 올바르지 않습니다.");
        }
        if (userId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_USER_ID);
        }
        this.id = id;
        this.campaignId = campaignId;
        this.slotId = slotId;
        this.userId = userId;
        this.benefit = benefit;
        this.issuedAt = issuedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** 관계 일치·기존 쿠폰·캠페인 최종 조건은 호출자가 같은 DB TX에서 검증한다. */
    public static Coupon create(long campaignId, long slotId, long userId, CouponBenefit benefit, Instant dbNow) {
        return new Coupon(null, campaignId, slotId, userId, benefit, dbNow);
    }

    public static Coupon restore(long id, long campaignId, long slotId, long userId,
                                 CouponBenefit benefit, Instant issuedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("저장된 쿠폰 ID는 양수여야 합니다.");
        }
        return new Coupon(id, campaignId, slotId, userId, benefit, issuedAt);
    }

    public Long getId() { return id; }
    public long getCampaignId() { return campaignId; }
    public long getSlotId() { return slotId; }
    public long getUserId() { return userId; }
    public CouponBenefit getBenefit() { return benefit; }
    public Instant getIssuedAt() { return issuedAt; }
}
