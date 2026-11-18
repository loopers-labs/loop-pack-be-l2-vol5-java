package com.loopers.domain.coupon.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.coupon.enums.IssueRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 접수 상태 기계의 원본. Redis 는 이 테이블에서 다시 만들 수 있는 사본이다 (8주차 D-132).
 * 적재 · 상태 전이는 commerce-streamer 가 하고, 스키마는 이 엔티티가 만든다 (8주차 D-137, D-138).
 * slotNo 는 배정 기록이라 FAILED 여도 지우지 않고, 유일성은 쿠폰 테이블이 지킨다 (8주차 D-154)
 */
@Getter
@Entity
@Table(
    name = "coupon_issue_request",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_coupon_issue_request_campaign_user", columnNames = {"campaign_id", "user_id"}),
        @UniqueConstraint(name = "uk_coupon_issue_request_campaign_sequence", columnNames = {"campaign_id", "sequence"})
    },
    indexes = @Index(name = "idx_coupon_issue_request_status", columnList = "campaign_id, status, sequence")
)
public class CouponIssueRequest extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Redis 가 매긴 접수 순번. 선착순의 기준이다 (8주차 D-115) */
    @Column(nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueRequestStatus status;

    /** WAITING 은 순번, RESERVE 는 승격될 때 물려받은 슬롯 */
    @Column(name = "slot_no")
    private Integer slotNo;

    @Column(name = "promoted_at")
    private ZonedDateTime promotedAt;

    @Column(name = "coupon_id")
    private Long couponId;

    protected CouponIssueRequest() {}

    private CouponIssueRequest(Long campaignId, Long userId, long sequence, IssueRequestStatus status, Integer slotNo) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.sequence = sequence;
        this.status = status;
        this.slotNo = slotNo;
    }

    /** 순번 ≤ 한도는 당첨(WAITING, 슬롯 = 순번), 그 밖은 예비(RESERVE). 예비 범위 밖은 기록하지 않는다 (8주차 D-121) */
    public static CouponIssueRequest accepted(Long campaignId, Long userId, long sequence, int totalQuota) {
        if (sequence < 1) {
            throw new IllegalArgumentException("순번은 1 이상. sequence=" + sequence);
        }
        if (sequence <= totalQuota) {
            return new CouponIssueRequest(campaignId, userId, sequence, IssueRequestStatus.WAITING, (int) sequence);
        }
        return new CouponIssueRequest(campaignId, userId, sequence, IssueRequestStatus.RESERVE, null);
    }
}
