package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "coupon_campaigns")
public class CouponCampaignModel extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(name = "start_at", nullable = false)
    private ZonedDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private ZonedDateTime endAt;

    protected CouponCampaignModel() {
    }

    public CouponCampaignModel(String name, int totalQuota, ZonedDateTime startAt, ZonedDateTime endAt) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 이름은 비어있을 수 없습니다.");
        }
        if (totalQuota <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "발급 수량은 1 이상이어야 합니다.");
        }
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "종료 시각은 시작 시각보다 이후여야 합니다.");
        }
        this.name = name;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public String getName() { return name; }
    public int getTotalQuota() { return totalQuota; }
    public int getIssuedCount() { return issuedCount; }
    public ZonedDateTime getStartAt() { return startAt; }
    public ZonedDateTime getEndAt() { return endAt; }

    public boolean isQuotaExhausted() {
        return issuedCount >= totalQuota;
    }
}
