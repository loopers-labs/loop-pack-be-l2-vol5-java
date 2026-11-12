package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 선착순 쿠폰 이벤트 (D-5). 줄·당첨자 수·발급 기간을 갖는다. 할인 규칙은 {@link Coupon}이 갖고, 당첨자는 캠페인의 쿠폰을 전부 받는다(쿠폰팩).
 * <p>
 * 생성 뒤 바뀌는 값은 발급 수뿐이고, 발급 수는 consumer의 조건부 UPDATE 한 경로로만 바뀐다 (D-5, ADR-W3-01과 다른 이유).
 */
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaign extends BaseEntity {

    static final int NAME_MAX_LENGTH = 100;
    /** 줄 길이 제한을 주지 않으면 당첨자 수의 이 배수 (D-4-5) */
    public static final int DEFAULT_QUEUE_CAPACITY_MULTIPLIER = 10;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    @Comment("캠페인 이름, 1–100자 (CPN-01)")
    private String name;

    @Column(name = "total_quantity", nullable = false)
    @Comment("당첨자 수 — 쿠폰팩을 받는 사람 수, 1 이상 (CPN-01)")
    private int totalQuantity;

    @Column(name = "issued_count", nullable = false)
    @Comment("지금까지 발급한 당첨자 수. consumer의 조건부 UPDATE로만 바뀐다 (D-5)")
    private int issuedCount;

    @Column(name = "queue_capacity", nullable = false)
    @Comment("지금 기다릴 수 있는 최대 인원(ZCARD 기준), 당첨자 수 이상 (CPN-02, D-4-5)")
    private int queueCapacity;

    @Column(name = "issue_start_at", nullable = false)
    @Comment("발급 시작 시각 (포함)")
    private ZonedDateTime issueStartAt;

    @Column(name = "issue_end_at", nullable = false)
    @Comment("발급 마감 시각 (제외)")
    private ZonedDateTime issueEndAt;

    protected CouponCampaign() {}

    /**
     * CPN-01·02: 이름 1–100자, 당첨자 수 1 이상, 시작 &lt; 마감. 줄 길이 제한은 당첨자 수 이상이고, 없으면 당첨자 수 × 10.
     */
    public CouponCampaign(String name, int totalQuantity, Integer queueCapacity, ZonedDateTime issueStartAt, ZonedDateTime issueEndAt) {
        this.name = validName(name);
        this.totalQuantity = validTotalQuantity(totalQuantity);
        this.queueCapacity = validQueueCapacity(queueCapacity, totalQuantity);
        validPeriod(issueStartAt, issueEndAt);
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
        this.issuedCount = 0;
    }

    /**
     * 발급 기간 안인가. 시작 시각은 포함하고 마감 시각은 제외한다.
     */
    public boolean isOpenAt(ZonedDateTime now) {
        return !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("캠페인 이름은 비어 있을 수 없습니다.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw badRequest("캠페인 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static int validTotalQuantity(int totalQuantity) {
        if (totalQuantity < 1) {
            throw badRequest("당첨자 수는 1 이상이어야 합니다.");
        }
        return totalQuantity;
    }

    private static int validQueueCapacity(Integer queueCapacity, int totalQuantity) {
        if (queueCapacity == null) {
            return Math.multiplyExact(totalQuantity, DEFAULT_QUEUE_CAPACITY_MULTIPLIER);
        }
        if (queueCapacity < totalQuantity) {
            throw badRequest("줄 길이 제한은 당첨자 수(" + totalQuantity + ") 이상이어야 합니다.");
        }
        return queueCapacity;
    }

    private static void validPeriod(ZonedDateTime start, ZonedDateTime end) {
        if (start == null || end == null) {
            throw badRequest("발급 시작·마감 시각은 필수입니다.");
        }
        if (!start.isBefore(end)) {
            throw badRequest("발급 시작 시각은 마감 시각보다 앞서야 합니다.");
        }
    }

    private static CoreException badRequest(String message) {
        return new CoreException(ErrorType.BAD_REQUEST, message);
    }

    public String getName() {
        return name;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public ZonedDateTime getIssueStartAt() {
        return issueStartAt;
    }

    public ZonedDateTime getIssueEndAt() {
        return issueEndAt;
    }
}
