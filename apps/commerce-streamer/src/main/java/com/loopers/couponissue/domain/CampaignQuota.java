package com.loopers.couponissue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 캠페인의 당첨자 수와 발급 수만 본다 (D-5). 발급 수는 조건부 UPDATE 한 문장으로만 바꾼다 — 엔티티로 읽고 고치면 갱신 손실이 난다.
 * 테이블의 주인은 commerce-api의 CouponCampaign이다.
 */
@Entity
@Table(name = "coupon_campaign")
public class CampaignQuota {

    @Id
    private Long id;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    protected CampaignQuota() {}

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }
}
