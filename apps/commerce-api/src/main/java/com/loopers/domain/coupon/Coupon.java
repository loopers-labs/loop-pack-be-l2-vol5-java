package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.Instant;
import java.util.Objects;

/**
 * 발급된 쿠폰 한 장의 기록. 쿠폰은 발급할 때 만들어지며 사용자와 발급 시각이 항상 있다.
 * 발급 뒤에는 소유자와 발급 시각을 바꾸지 않는다.
 */
public class Coupon {

    private final Long id;
    private final Long campaignId;
    private final Long userId;
    private final Instant issuedAt;

    private Coupon(Long id, Long campaignId, Long userId, Instant issuedAt) {
        if (campaignId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰의 캠페인 ID는 필수입니다.");
        }
        if (userId == null || issuedAt == null) {
            throw new IllegalArgumentException("쿠폰은 사용자와 발급 시각을 함께 가져야 합니다.");
        }
        this.id = id;
        this.campaignId = campaignId;
        this.userId = userId;
        this.issuedAt = issuedAt;
    }

    /**
     * 발급할 때 쿠폰을 만든다. 쿠폰 행은 발급된 쿠폰만 나타내므로 사용자와 발급 시각이 반드시 있다.
     */
    public static Coupon create(Long campaignId, Long userId, Instant issuedAt) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰을 받을 사용자 ID는 필수입니다.");
        }
        return new Coupon(null, campaignId, userId, Objects.requireNonNull(issuedAt, "issuedAt"));
    }

    public static Coupon reconstitute(Long id, Long campaignId, Long userId, Instant issuedAt) {
        return new Coupon(id, campaignId, userId, issuedAt);
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getUserId() { return userId; }
    public Instant getIssuedAt() { return issuedAt; }
}
