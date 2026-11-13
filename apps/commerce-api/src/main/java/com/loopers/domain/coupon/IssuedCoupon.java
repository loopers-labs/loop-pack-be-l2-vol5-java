package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 한 사용자가 한 캠페인에서 받은 쿠폰 한 장(docs/week8/decisions.md INV-802, INV-806).
 *
 * - 혜택은 발급 순간 캠페인에서 복사한 값이다(D-15). 캠페인 혜택이 나중에 바뀌어도 이 행은 바뀌지 않는다 — 바꾸는 메서드가 없고
 *   컬럼도 updatable=false다. 1주차 INV-004("확정 뒤의 값은 다시 계산하지 않는다")와 같은 원칙이다.
 * - 만드는 곳은 {@link CouponCampaign#issueTo} 하나다. 발급 수 증가와 쿠폰 생성이 한 메서드에서 같이 일어나야 둘이 어긋나지 않는다.
 */
// uk(campaign_id, user_id): 한 사용자 한 장의 마지막 방어선이다(INV-802, D-8). 앞단 조회는 지름길일 뿐 중복을 막지 못한다.
// idx(user_id, issued_at, id): 보유 목록 "요청자 것만, 발급 시각 내림차순, 같으면 id 내림차순" 경로다(D-16).
@Entity
@Table(
    name = "issued_coupon",
    uniqueConstraints = @UniqueConstraint(name = IssuedCoupon.UK_CAMPAIGN_USER, columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_issued_coupon_user_id_issued_at_id", columnList = "user_id, issued_at, id")
)
public class IssuedCoupon extends BaseEntity {

    /** (campaign_id, user_id) unique 이름. 저장소 구현이 "이 제약 위반"만 골라 중복 발급으로 바꿀 때 이 이름으로 가린다. */
    public static final String UK_CAMPAIGN_USER = "uk_issued_coupon_campaign_user";

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "description",
            column = @Column(name = "benefit_description", nullable = false, updatable = false)),
        @AttributeOverride(name = "discountAmount",
            column = @Column(name = "discount_amount", nullable = false, updatable = false))
    })
    private CouponBenefit benefit;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private ZonedDateTime issuedAt;

    protected IssuedCoupon() {}

    private IssuedCoupon(Long campaignId, Long userId, CouponBenefit benefit, ZonedDateTime issuedAt) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.benefit = benefit;
        this.issuedAt = issuedAt;
    }

    /** {@link CouponCampaign#issueTo}만 부른다. */
    static IssuedCoupon issue(Long campaignId, Long userId, CouponBenefit benefit, ZonedDateTime issuedAt) {
        if (campaignId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰의 캠페인 ID는 비어있을 수 없습니다.");
        }
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰을 받을 사용자 ID는 비어있을 수 없습니다.");
        }
        if (benefit == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 혜택은 비어있을 수 없습니다.");
        }
        if (issuedAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급 시각은 비어있을 수 없습니다.");
        }
        return new IssuedCoupon(campaignId, userId, benefit, issuedAt);
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public CouponBenefit getBenefit() {
        return benefit;
    }

    public ZonedDateTime getIssuedAt() {
        return issuedAt;
    }
}
