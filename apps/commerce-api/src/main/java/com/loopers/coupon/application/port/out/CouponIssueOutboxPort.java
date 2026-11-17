package com.loopers.coupon.application.port.out;

import com.loopers.coupon.domain.CouponIssueOutbox;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 당첨 기록 저장소 (D-2).
 */
public interface CouponIssueOutboxPort {

    /**
     * 이 사용자의 당첨 기록이 없으면 PENDING으로 남긴다. 이미 있으면 아무것도 하지 않는다 (UNIQUE(campaign_id, user_id)).
     * 같은 사람을 여러 경로가 동시에 기록해도 예외 없이 한 행만 남는다. 새로 남겼으면 true.
     */
    boolean appendIfAbsent(Long campaignId, Long userId, ZonedDateTime now);

    CouponIssueOutbox save(CouponIssueOutbox outbox);

    Optional<CouponIssueOutbox> findById(Long id);

    /** relay 한 회차가 고를 행: PENDING이거나 기한이 지난 SENDING이고 next_attempt_at ≤ now. id 순서로 최대 limit개 */
    List<CouponIssueOutbox> findDue(ZonedDateTime now, int limit);

    /** 이 캠페인의 당첨자 전부 (Redis 복구, D-4-7) */
    Set<Long> findUserIds(Long campaignId);

    /** 이 사용자들 중 이미 기록된 사람 (맞춰 보기, D-2) */
    Set<Long> findRecordedUserIds(Long campaignId, Collection<Long> userIds);
}
