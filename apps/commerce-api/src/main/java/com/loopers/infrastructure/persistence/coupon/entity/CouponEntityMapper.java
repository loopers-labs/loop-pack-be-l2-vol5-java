package com.loopers.infrastructure.persistence.coupon.entity;

import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.model.CouponBenefit;
import org.springframework.stereotype.Component;

@Component
public class CouponEntityMapper {
    public Coupon toDomain(CouponJpaEntity entity) {
        return Coupon.restore(entity.getId(), entity.getCampaignId(), entity.getSlotId(), entity.getUserId(),
            new CouponBenefit(entity.getBenefitDescription(), entity.getDiscountAmount()), entity.getIssuedAt());
    }

    public CouponJpaEntity toNewEntity(Coupon coupon) {
        return new CouponJpaEntity(coupon.getCampaignId(), coupon.getSlotId(), coupon.getUserId(),
            coupon.getBenefit().description(), coupon.getBenefit().discountAmount(), coupon.getIssuedAt());
    }
}
