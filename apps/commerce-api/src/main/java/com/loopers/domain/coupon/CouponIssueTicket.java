package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 202 로 약속한 당첨의 기록. Redis 상태를 잃어도 약속한 당첨을 DB 에서 되살리기 위해 남긴다.
 */
@Getter
@Entity
@Table(
    name = "coupon_issue_tickets",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_issue_tickets_campaign_user", columnNames = {"campaign_id", "user_id"})
)
public class CouponIssueTicket extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "won_at", nullable = false)
    private ZonedDateTime wonAt;

    protected CouponIssueTicket() {}

    public static CouponIssueTicket record(Long campaignId, Long userId, ZonedDateTime wonAt) {
        CouponIssueTicket ticket = new CouponIssueTicket();
        ticket.campaignId = campaignId;
        ticket.userId = userId;
        ticket.wonAt = wonAt;
        return ticket;
    }
}
