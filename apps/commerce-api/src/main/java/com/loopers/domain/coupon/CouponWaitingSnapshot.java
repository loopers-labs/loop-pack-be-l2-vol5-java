package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.Objects;

/**
 * 한 사용자의 현재 대기 항목을 읽은 시점의 모습(설계 §12.11). 상태에 필요 없는 필드는 null 이다.
 * position 은 WAITING 에만(맨 앞 1), expiresAt 은 READY 에만 둔다. couponId 는 DB 에서 확인한 값만 채우며 Redis 계층은 항상 null 이다.
 */
public record CouponWaitingSnapshot(
    Long campaignId,
    String waitingId,
    CouponWaitingState status,
    Long position,
    Instant expiresAt,
    Long couponId
) {

    public CouponWaitingSnapshot {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(status, "status");
        if ((position != null) != (status == CouponWaitingState.WAITING)) {
            throw new IllegalArgumentException("position 은 WAITING 에만 있어야 합니다. [status = " + status + ", position = " + position + "]");
        }
        if (position != null && position < 1) {
            throw new IllegalArgumentException("position 은 1 이상이어야 합니다. [position = " + position + "]");
        }
        if ((expiresAt != null) != (status == CouponWaitingState.READY)) {
            throw new IllegalArgumentException("expiresAt 은 READY 에만 있어야 합니다. [status = " + status + ", expiresAt = " + expiresAt + "]");
        }
    }
}
