package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.Instant;
import java.util.Objects;

public class Coupon {

    private final Long id;
    private final Long campaignId;
    private Long userId;
    private Instant issuedAt;

    private Coupon(Long id, Long campaignId, Long userId, Instant issuedAt) {
        if (campaignId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰의 캠페인 ID는 필수입니다.");
        }
        if ((userId == null) != (issuedAt == null)) {
            throw new IllegalArgumentException("쿠폰 사용자와 발급 시각은 함께 기록되어야 합니다.");
        }
        this.id = id;
        this.campaignId = campaignId;
        this.userId = userId;
        this.issuedAt = issuedAt;
    }

    public static Coupon create(Long campaignId) {
        return new Coupon(null, campaignId, null, null);
    }

    public static Coupon reconstitute(Long id, Long campaignId, Long userId, Instant issuedAt) {
        return new Coupon(id, campaignId, userId, issuedAt);
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getUserId() { return userId; }
    public Instant getIssuedAt() { return issuedAt; }

    public boolean isIssued() {
        return userId != null;
    }

    public void issueTo(Long userId, Instant issueNow) {
        if (isIssued()) {
            throw new CoreException(ErrorType.CONFLICT, "이미 발급된 쿠폰입니다.");
        }
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰을 받을 사용자 ID는 필수입니다.");
        }
        Instant issuedTime = Objects.requireNonNull(issueNow, "issueNow");
        this.userId = userId;
        this.issuedAt = issuedTime;
    }
}
