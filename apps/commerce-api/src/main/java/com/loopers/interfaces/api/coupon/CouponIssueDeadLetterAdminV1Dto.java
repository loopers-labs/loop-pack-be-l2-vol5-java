package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.domain.coupon.CouponIssueDeadLetter;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;

import java.time.Instant;
import java.util.List;

public class CouponIssueDeadLetterAdminV1Dto {

    private static final int MAX_REPLAY_IDS = 100;

    public record ReplayRequest(List<Long> ids) {
        public ReplayRequest {
            if (ids == null || ids.isEmpty() || ids.contains(null)) {
                throw new IllegalArgumentException("ids 는 하나 이상이어야 하고 비어 있는 값이 없어야 합니다.");
            }
            if (ids.size() > MAX_REPLAY_IDS) {
                throw new IllegalArgumentException("ids 는 한 번에 " + MAX_REPLAY_IDS + " 개까지입니다.");
            }
            ids = ids.stream().distinct().toList();
        }
    }

    public record ReplayResponse(int replayed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadLetterResponse(Long id, String messageKey, Long campaignId, Long userId, Integer issueNumber,
                                     String error, String status, Instant receivedAt, Instant replayedAt) {
        public static DeadLetterResponse from(CouponIssueDeadLetter deadLetter) {
            return new DeadLetterResponse(deadLetter.id(), deadLetter.messageKey(),
                deadLetter.issue().map(CouponIssue::campaignId).orElse(null),
                deadLetter.issue().map(CouponIssue::userId).orElse(null),
                deadLetter.issue().map(CouponIssue::issueNumber).orElse(null),
                deadLetter.error(), deadLetter.status().name(), deadLetter.receivedAt(), deadLetter.replayedAt());
        }

        public static List<DeadLetterResponse> listOf(List<CouponIssueDeadLetter> deadLetters) {
            return deadLetters.stream().map(DeadLetterResponse::from).toList();
        }
    }
}
