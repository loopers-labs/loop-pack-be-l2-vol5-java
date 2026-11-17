package com.loopers.infrastructure.persistence.coupon.entity;

import com.loopers.domain.coupon.model.CouponSlot;
import org.springframework.stereotype.Component;

@Component
public class CouponSlotEntityMapper {
    public CouponSlot toDomain(CouponSlotJpaEntity entity) {
        return CouponSlot.restore(entity.getId(), entity.getCampaignId(), entity.getState());
    }

    public CouponSlotJpaEntity toNewEntity(CouponSlot slot) {
        return new CouponSlotJpaEntity(slot.getCampaignId(), slot.getState());
    }

    public void apply(CouponSlot slot, CouponSlotJpaEntity entity) {
        entity.apply(slot.getState());
    }
}
