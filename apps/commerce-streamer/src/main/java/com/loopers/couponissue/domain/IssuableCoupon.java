package com.loopers.couponissue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * 발급에 필요한 쿠폰 정보만 읽는다 (D-8: 쿠폰 생성·검증은 commerce-api가 하고, streamer는 발급만 한다).
 * 테이블의 주인은 commerce-api의 Coupon이다. streamer는 이 행을 바꾸지 않는다.
 */
@Entity
@Table(name = "coupon")
public class IssuableCoupon {

    @Id
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_type", nullable = false, length = 20)
    private ValidityType validityType;

    @Column(name = "valid_until")
    private ZonedDateTime validUntil;

    @Column(name = "valid_days")
    private Integer validDays;

    protected IssuableCoupon() {}

    public IssuableCoupon(Long id, Long campaignId, ValidityType validityType, ZonedDateTime validUntil, Integer validDays) {
        this.id = id;
        this.campaignId = campaignId;
        this.validityType = validityType;
        this.validUntil = validUntil;
        this.validDays = validDays;
    }

    /**
     * CPN-07: FIXED_PERIOD는 끝 시각, DAYS_FROM_ISSUE는 발급 시각 + 일수 (commerce-api Coupon과 같은 규칙).
     */
    public ZonedDateTime expiresAtWhenIssuedAt(ZonedDateTime issuedAt) {
        return switch (validityType) {
            case FIXED_PERIOD -> validUntil;
            case DAYS_FROM_ISSUE -> issuedAt.plusDays(validDays);
        };
    }

    public Long getId() {
        return id;
    }

    public enum ValidityType {
        FIXED_PERIOD,
        DAYS_FROM_ISSUE
    }
}
