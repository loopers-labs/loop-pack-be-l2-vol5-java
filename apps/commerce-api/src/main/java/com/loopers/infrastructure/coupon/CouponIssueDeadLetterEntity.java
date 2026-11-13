package com.loopers.infrastructure.coupon;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.coupon.CouponIssueDeadLetter;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(
    name = "coupon_issue_dlt_log",
    uniqueConstraints = @UniqueConstraint(name = CouponIssueDeadLetterEntity.UK_MESSAGE_KEY, columnNames = "message_key"),
    indexes = @Index(name = "idx_coupon_issue_dlt_log_received", columnList = "received_at, id")
)
class CouponIssueDeadLetterEntity extends AuditEntity {

    static final String UK_MESSAGE_KEY = "uk_coupon_issue_dlt_log_message_key";
    private static final int ERROR_LENGTH = 1000;

    @Column(name = "message_key", nullable = false, updatable = false, length = 200)
    private String messageKey;

    @Column(name = "campaign_id", updatable = false)
    private Long campaignId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "issue_number", updatable = false)
    private Integer issueNumber;

    @Column(name = "reserved_at", updatable = false)
    private Instant reservedAt;

    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "error_message", nullable = false, updatable = false, length = ERROR_LENGTH)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CouponIssueDeadLetter.Status status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    protected CouponIssueDeadLetterEntity() {}

    static CouponIssueDeadLetterEntity from(CouponIssueDeadLetter deadLetter) {
        CouponIssueDeadLetterEntity entity = new CouponIssueDeadLetterEntity();
        entity.messageKey = deadLetter.messageKey();
        deadLetter.issue().ifPresent(issue -> {
            entity.campaignId = issue.campaignId();
            entity.userId = issue.userId();
            entity.issueNumber = issue.issueNumber();
            entity.reservedAt = issue.reservedAt();
        });
        entity.payload = deadLetter.payload();
        entity.errorMessage = deadLetter.error().length() > ERROR_LENGTH
            ? deadLetter.error().substring(0, ERROR_LENGTH)
            : deadLetter.error();
        entity.status = deadLetter.status();
        entity.receivedAt = deadLetter.receivedAt();
        entity.replayedAt = deadLetter.replayedAt();
        return entity;
    }

    CouponIssueDeadLetter toDomain() {
        Optional<CouponIssue> issue = campaignId == null
            ? Optional.empty()
            : Optional.of(new CouponIssue(campaignId, userId, issueNumber, reservedAt));
        return new CouponIssueDeadLetter(getId(), messageKey, issue, payload, errorMessage, status, receivedAt, replayedAt);
    }
}
