package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 당첨 기록 한 건 = outbox 행 한 개 (D-2). Redis 당첨자 Set이 원본이고, 이 표가 그 명단을 따라잡는다(앞으로 채우기).
 * <p>
 * UNIQUE(campaign_id, user_id): 발급 API·다시 요청·맞춰 보기가 같은 사람을 여러 번 기록해도 한 행만 남는다.
 * 행은 발행 뒤에도 지우지 않는다 — Redis가 사라지면 이 표로 당첨자를 되살린다 (D-4-7).
 * 전달 상태 전이는 W7 OrderOutbox와 같다 (lease, 3번 실패 BLOCKED).
 */
@Entity
@Table(
    name = "coupon_issue_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_issue_outbox_campaign_user", columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupon_issue_outbox_due", columnList = "status, next_attempt_at, id")
)
public class CouponIssueOutbox extends BaseEntity {

    /** SENDING으로 맡는 기한. relay 대기(send-wait 35초)보다 길어야 한다 (W7 D-1) */
    public static final Duration LEASE = Duration.ofMinutes(1);
    public static final int MAX_ATTEMPTS = 3;
    static final int LAST_ERROR_MAX_LENGTH = 1000;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "message_key", nullable = false, length = 50)
    @Comment("partition을 고르는 key — userId 문자열. 사용자 사이 순서는 필요 없어 partition에 고르게 퍼진다")
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    @Comment("보낼 JSON 원문 {campaignId, userId}")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CouponIssueOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    @Comment("PENDING: 다음 시도 가능 시각 · SENDING: 맡은 기한(lease)")
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    protected CouponIssueOutbox() {}

    /**
     * relay가 이 행을 맡는다. 차례가 된 PENDING, 또는 기한이 지난 SENDING만.
     */
    public void startSending(ZonedDateTime now) {
        if (status != CouponIssueOutboxStatus.PENDING && status != CouponIssueOutboxStatus.SENDING) {
            throw conflict("PENDING·SENDING만 맡을 수 있습니다", now);
        }
        if (nextAttemptAt.isAfter(now)) {
            throw conflict("아직 차례가 아닙니다 (next_attempt_at=" + nextAttemptAt + ")", now);
        }
        this.status = CouponIssueOutboxStatus.SENDING;
        this.nextAttemptAt = now.plus(LEASE);
    }

    /**
     * broker가 저장을 확인했다(ack). SENDING만.
     */
    public void markPublished(ZonedDateTime now) {
        requireSending("성공 기록", now);
        this.status = CouponIssueOutboxStatus.PUBLISHED;
        this.publishedAt = now;
    }

    /**
     * send 실패. MAX_ATTEMPTS번째면 BLOCKED, 아니면 delay 뒤 다시 차례가 된다.
     */
    public void recordFailure(String error, ZonedDateTime now, Duration delay) {
        requireSending("실패 기록", now);
        this.attemptCount++;
        this.lastError = error == null || error.length() <= LAST_ERROR_MAX_LENGTH ? error : error.substring(0, LAST_ERROR_MAX_LENGTH);
        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = CouponIssueOutboxStatus.BLOCKED;
            return;
        }
        this.status = CouponIssueOutboxStatus.PENDING;
        this.nextAttemptAt = now.plus(delay);
    }

    private void requireSending(String action, ZonedDateTime now) {
        if (status != CouponIssueOutboxStatus.SENDING) {
            throw conflict(action + "은 SENDING에서만 할 수 있습니다", now);
        }
    }

    private CoreException conflict(String reason, ZonedDateTime now) {
        return new CoreException(ErrorType.CONFLICT, reason + ". outboxId=" + getId() + ", status=" + status + ", now=" + now);
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public CouponIssueOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public ZonedDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }
}
