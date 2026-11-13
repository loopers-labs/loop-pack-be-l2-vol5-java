package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.EnumNameConverter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Check;

/**
 * 발급 행사. 발급 수 증가는 저장 포트의 조건부 UPDATE 책임이다.
 * Entity 를 바꿔 flush 하면 다른 TX 의 증가를 덮어쓸 수 있어 변경 행동을 두지 않는다.
 */
@Entity
@Table(name = "coupon_campaign")
@Check(name = "ck_coupon_campaign_quota",
    constraints = "total_quota >= 1 AND issued_count >= 0 AND issued_count <= total_quota")
@Getter
public class CouponCampaignModel extends BaseEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Convert(converter = StatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private CouponCampaignStatus status;

    @Column(name = "total_quota", nullable = false)
    private long totalQuota;

    @Column(name = "issued_count", nullable = false)
    private long issuedCount;

    @Convert(converter = BenefitTypeConverter.class)
    @Column(name = "benefit_type", nullable = false, length = 16)
    private CouponBenefitType benefitType;

    @Column(name = "benefit_value", nullable = false)
    private long benefitValue;

    protected CouponCampaignModel() {}

    private CouponCampaignModel(String name, long totalQuota, CouponBenefitType benefitType, long benefitValue) {
        this.name = name;
        this.status = CouponCampaignStatus.ACTIVE;
        this.totalQuota = totalQuota;
        this.issuedCount = 0L;
        this.benefitType = benefitType;
        this.benefitValue = benefitValue;
    }

    public static CouponCampaignModel create(String name, long totalQuota, CouponBenefitType benefitType, long benefitValue) {
        CouponCampaignModel campaign = new CouponCampaignModel(normalizeName(name), totalQuota, benefitType, benefitValue);
        campaign.guard();
        return campaign;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "캠페인 이름 필수");
        }
        return name.trim();
    }

    /** 캠페인 생성과 캐시 값 검증이 같은 규칙을 쓴다. */
    public static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && name.length() <= NAME_MAX_LENGTH && name.equals(name.trim());
    }

    public boolean isActive() {
        return status == CouponCampaignStatus.ACTIVE;
    }

    /** 이 값으로 수량을 확보하지 않는다(조건부 UPDATE 책임). */
    public boolean isSoldOut() {
        return issuedCount >= totalQuota;
    }

    @Override
    protected void guard() {
        if (!isValidName(name)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "캠페인 이름은 앞뒤 공백을 제외하고 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        if (status == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "캠페인 상태 필수");
        }
        if (totalQuota < 1L) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "캠페인 총량은 1 이상이어야 합니다. [totalQuota = " + totalQuota + "]");
        }
        if (issuedCount < 0L || issuedCount > totalQuota) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "발급 수는 0 이상 총량 이하여야 합니다.");
        }
        CouponBenefits.require(benefitType, benefitValue);
    }

    public static class StatusConverter extends EnumNameConverter<CouponCampaignStatus> {
        public StatusConverter() {
            super(CouponCampaignStatus.class);
        }
    }

    public static class BenefitTypeConverter extends EnumNameConverter<CouponBenefitType> {
        public BenefitTypeConverter() {
            super(CouponBenefitType.class);
        }
    }
}
