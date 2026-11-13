package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/** 작업 큐에서 "받음"(202)이라고 답했지만 Worker가 확정 거절한 기록. 사용자가 상태 조회로 이유를 본다. */
@Getter
@Entity
@Table(
    name = "coupon_issue_cancellation",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_cancel_campaign_user", columnNames = {"campaign_id", "user_id"})
)
public class CouponIssueCancellationModel extends BaseEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String reason;

    protected CouponIssueCancellationModel() {}

    public CouponIssueCancellationModel(Long campaignId, Long userId, String reason) {
        if (campaignId == null || campaignId <= 0) {
            throw new IllegalArgumentException("캠페인 ID는 양수여야 합니다.");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("취소 이유는 비어있을 수 없습니다.");
        }
        this.campaignId = campaignId;
        this.userId = userId;
        this.reason = reason;
    }
}
