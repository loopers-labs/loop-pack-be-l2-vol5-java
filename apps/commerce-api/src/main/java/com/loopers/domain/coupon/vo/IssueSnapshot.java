package com.loopers.domain.coupon.vo;

import com.loopers.domain.coupon.enums.IssueState;

/**
 * 한 사용자의 접수 상태를 한 번에 읽은 결과.
 *
 * @param reservePosition RESERVE 일 때만. (순번 − 한도) − 승격된 인원 (8주차 D-149)
 * @param couponId        ISSUED 일 때만
 */
public record IssueSnapshot(IssueState state, long sequence, Integer reservePosition, Long couponId) {

    /** 품절 플래그가 서 있으면 예비는 위치와 상관없이 SOLD_OUT 으로 읽는다 (8주차 D-125) */
    public static IssueSnapshot of(IssueState stored, long sequence, Long couponId,
                                   boolean soldOut, long promotedCount, int totalQuota) {
        if (stored != IssueState.RESERVE) {
            return new IssueSnapshot(stored, sequence, null, couponId);
        }
        if (soldOut) {
            return new IssueSnapshot(IssueState.SOLD_OUT, sequence, null, null);
        }
        int position = (int) (sequence - totalQuota - promotedCount);
        return new IssueSnapshot(IssueState.RESERVE, sequence, position, null);
    }
}
