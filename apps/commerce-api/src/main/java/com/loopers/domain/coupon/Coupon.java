package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 사용자에게 발급된 쿠폰 (8주차 설계 §6).
 * - (campaign_id, user_id) UNIQUE 는 사용자당 1장(INV-W8-03)의 마지막 안전장치다. 위반 시 호출자는 제약 이름으로 식별해 기존 쿠폰을 반환한다
 * - expiresAt·benefitType·benefitValue·status 는 1주차 할인 적용 흐름이 소비할 수 있는 모양으로 저장하기 위한 컬럼이다. 이번 주차에서는 읽지 않는다
 */
@Getter
@Entity
@Table(
    name = "coupon",
    uniqueConstraints = @UniqueConstraint(name = Coupon.UK_CAMPAIGN_USER, columnNames = {"campaign_id", "user_id"})
)
public class Coupon extends BaseEntity {

    public static final String UK_CAMPAIGN_USER = "uk_coupon_campaign_user";

    public enum BenefitType {
        FIXED, RATE
    }

    public enum Status {
        ISSUED, USED
    }

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private ZonedDateTime issuedAt;

    @Column(nullable = false)
    private ZonedDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BenefitType benefitType;

    @Column(nullable = false)
    private long benefitValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected Coupon() {}

    /**
     * 캠페인이 정의한 혜택·만료 시각을 복사해 ISSUED 상태로 만든다.
     */
    public Coupon(CouponCampaign campaign, Long userId, ZonedDateTime issuedAt) {
        if (campaign == null || userId == null || issuedAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰의 캠페인·사용자·발급 시각은 필수입니다.");
        }
        this.campaignId = campaign.getId();
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = campaign.getCouponExpiresAt();
        this.benefitType = campaign.getBenefitType();
        this.benefitValue = campaign.getBenefitValue();
        this.status = Status.ISSUED;
    }
}
