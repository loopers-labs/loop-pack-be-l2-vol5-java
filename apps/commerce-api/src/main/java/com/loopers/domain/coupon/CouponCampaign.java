package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 정해진 쿠폰을 정해진 수량만 발급하는 행사.
 *
 * <p>issuedCount 는 엔티티로 올리지 않는다. 동시 발급이 서로의 값을 덮어쓰지 않도록 조건부 UPDATE 로만 올린다.
 */
@Getter
@Entity
@Table(name = "coupon_campaigns")
public class CouponCampaign extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "discount_amount", nullable = false))
    private Money discountAmount;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected CouponCampaign() {}

    public static CouponCampaign create(String name, Money discountAmount, int totalQuota) {
        if (discountAmount == null || discountAmount.isZero()) {
            throw new CoreException(ErrorType.INVALID_INPUT, "할인 금액은 1원 이상이어야 합니다.");
        }
        if (totalQuota < 1) {
            throw new CoreException(ErrorType.INVALID_INPUT, "발급 한도는 1 이상이어야 합니다.");
        }
        CouponCampaign campaign = new CouponCampaign();
        campaign.name = validatedName(name);
        campaign.discountAmount = discountAmount;
        campaign.totalQuota = totalQuota;
        campaign.issuedCount = 0;
        campaign.active = true;
        return campaign;
    }

    /** 신규 발급을 끝낸다. 이미 발급된 쿠폰은 그대로 남는다. */
    public void close() {
        this.active = false;
    }

    private static String validatedName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_INPUT, "캠페인 이름은 1자 이상 100자 이하여야 합니다.");
        }
        return trimmed;
    }
}
