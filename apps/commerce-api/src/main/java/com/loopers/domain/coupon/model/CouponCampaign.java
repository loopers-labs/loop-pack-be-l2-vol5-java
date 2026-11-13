package com.loopers.domain.coupon.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** DB에서 현재 캠페인을 잠근 뒤 발급 조건과 카운터를 검증하는 모델. 자체적으로 동시성을 보장하지 않는다. */
public final class CouponCampaign {
    private final Long id;
    private final String name;
    private boolean active;
    private final int totalQuota;
    private int issuedCount;
    private final CouponBenefit benefit;
    private final Instant startsAt;
    private final Instant endsAt;

    private CouponCampaign(Long id, String name, boolean active, int totalQuota, int issuedCount,
                           CouponBenefit benefit, Instant startsAt, Instant endsAt) {
        if (name == null) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_CAMPAIGN_NAME);
        }
        String normalizedName = name.strip();
        if (normalizedName.isEmpty() || normalizedName.codePointCount(0, normalizedName.length()) > 100) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_CAMPAIGN_NAME);
        }
        if (totalQuota <= 0 || issuedCount < 0 || issuedCount > totalQuota) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_QUOTA);
        }
        if (startsAt == null || endsAt == null) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_PERIOD);
        }
        Instant normalizedStart = startsAt.truncatedTo(ChronoUnit.MICROS);
        Instant normalizedEnd = endsAt.truncatedTo(ChronoUnit.MICROS);
        if (!normalizedStart.isBefore(normalizedEnd)) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_PERIOD);
        }
        this.id = id;
        this.name = normalizedName;
        this.active = active;
        this.totalQuota = totalQuota;
        this.issuedCount = issuedCount;
        this.benefit = Objects.requireNonNull(benefit);
        this.startsAt = normalizedStart;
        this.endsAt = normalizedEnd;
    }

    public static CouponCampaign create(String name, int totalQuota, CouponBenefit benefit,
                                        Instant startsAt, Instant endsAt) {
        return new CouponCampaign(null, name, false, totalQuota, 0, benefit, startsAt, endsAt);
    }

    public static CouponCampaign restore(long id, String name, boolean active, int totalQuota, int issuedCount,
                                         CouponBenefit benefit, Instant startsAt, Instant endsAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("저장된 캠페인 ID는 양수여야 합니다.");
        }
        return new CouponCampaign(id, name, active, totalQuota, issuedCount, benefit, startsAt, endsAt);
    }

    /** 실제 준비 슬롯 수는 같은 TX에서 호출자가 확인한다. 재활성도 기존 발급 수를 보존한다. */
    public void activate(long preparedSlotCount) {
        if (preparedSlotCount != totalQuota) {
            throw new DomainException(DomainErrorCode.COUPON_SLOTS_NOT_READY);
        }
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    /** 현재 캠페인 잠금 획득 뒤 조회한 DB 시각을 전달한다. 앱 시계를 직접 읽지 않는다. */
    public void ensureIssuable(Instant dbNow) {
        Objects.requireNonNull(dbNow);
        if (dbNow.isBefore(startsAt)) {
            throw new DomainException(DomainErrorCode.COUPON_CAMPAIGN_NOT_STARTED);
        }
        if (!dbNow.isBefore(endsAt)) {
            throw new DomainException(DomainErrorCode.COUPON_CAMPAIGN_ENDED);
        }
        if (!active) {
            throw new DomainException(DomainErrorCode.COUPON_CAMPAIGN_INACTIVE);
        }
        if (issuedCount >= totalQuota) {
            throw new DomainException(DomainErrorCode.COUPON_SOLD_OUT);
        }
    }

    /** 슬롯 변경·쿠폰 INSERT와 같은 TX 마지막에 호출하고 명시적으로 저장해야 한다. */
    public void recordIssue(Instant dbNow) {
        ensureIssuable(dbNow);
        issuedCount++;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public int getTotalQuota() { return totalQuota; }
    public int getIssuedCount() { return issuedCount; }
    public CouponBenefit getBenefit() { return benefit; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
}
