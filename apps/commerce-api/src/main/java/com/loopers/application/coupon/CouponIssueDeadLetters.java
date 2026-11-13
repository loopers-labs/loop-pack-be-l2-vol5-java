package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueDeadLetter;
import com.loopers.domain.coupon.CouponIssueDeadLetterService;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueDeadLetters {

    private static final int RECENT_LIMIT = 100;

    private final CouponIssueDeadLetterService deadLetterService;
    private final CouponIssuePublisher couponIssuePublisher;
    private final CouponTransactions couponTransactions;

    public void record(String messageKey, Optional<CouponIssue> issue, String payload, String error, Instant receivedAt) {
        boolean inserted = deadLetterService.record(
            CouponIssueDeadLetter.received(messageKey, issue, payload, error, receivedAt));
        if (inserted) {
            log.warn("DLT 메시지를 로그에 남겼다: messageKey={}, campaignId={}, userId={}, error={}",
                messageKey, issue.map(CouponIssue::campaignId).orElse(null), issue.map(CouponIssue::userId).orElse(null),
                error);
        }
    }

    public List<CouponIssueDeadLetter> findRecent() {
        return deadLetterService.findRecent(RECENT_LIMIT);
    }

    public int replay(List<Long> ids) {
        int replayed = 0;
        for (CouponIssueDeadLetter deadLetter : deadLetterService.findByIds(ids)) {
            if (!deadLetter.isReplayable()) {
                continue;
            }
            CouponIssuePublisher.Outcome outcome = couponIssuePublisher.publish(deadLetter.issue().orElseThrow());
            if (!(outcome instanceof CouponIssuePublisher.Outcome.Published)) {
                log.warn("DLT 메시지를 다시 보내지 못했다. PENDING 으로 둔다: id={}, outcome={}", deadLetter.id(), outcome);
                continue;
            }
            if (couponTransactions.markDeadLetterReplayed(deadLetter.id(), Instant.now())) {
                replayed++;
            }
        }
        return replayed;
    }
}
