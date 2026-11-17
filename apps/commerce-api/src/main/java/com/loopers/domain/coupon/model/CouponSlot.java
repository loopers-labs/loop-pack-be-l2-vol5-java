package com.loopers.domain.coupon.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

/** 영속 쿠폰 재고 한 장. 실행 종료 때 반환하는 Semaphore permit과 다르다. */
public final class CouponSlot {
    private final Long id;
    private final long campaignId;
    private CouponSlotState state;

    private CouponSlot(Long id, long campaignId, CouponSlotState state) {
        if (campaignId <= 0 || state == null) {
            throw new IllegalArgumentException("발급 슬롯의 캠페인과 상태가 올바르지 않습니다.");
        }
        this.id = id;
        this.campaignId = campaignId;
        this.state = state;
    }

    public static CouponSlot create(long campaignId) {
        return new CouponSlot(null, campaignId, CouponSlotState.AVAILABLE);
    }

    public static CouponSlot restore(long id, long campaignId, CouponSlotState state) {
        if (id <= 0) {
            throw new IllegalArgumentException("저장된 발급 슬롯 ID는 양수여야 합니다.");
        }
        return new CouponSlot(id, campaignId, state);
    }

    public void markIssued() {
        if (state != CouponSlotState.AVAILABLE) {
            throw new DomainException(DomainErrorCode.COUPON_SLOT_ALREADY_ISSUED);
        }
        state = CouponSlotState.ISSUED;
    }

    public Long getId() { return id; }
    public long getCampaignId() { return campaignId; }
    public CouponSlotState getState() { return state; }
}
