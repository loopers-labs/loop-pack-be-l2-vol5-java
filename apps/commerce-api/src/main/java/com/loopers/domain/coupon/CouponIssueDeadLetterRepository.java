package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.List;

public interface CouponIssueDeadLetterRepository {

    boolean insertIfAbsent(CouponIssueDeadLetter deadLetter);

    List<CouponIssueDeadLetter> findRecent(int limit);

    List<CouponIssueDeadLetter> findByIds(List<Long> ids);

    void markReplayed(Long id, Instant replayedAt);
}
