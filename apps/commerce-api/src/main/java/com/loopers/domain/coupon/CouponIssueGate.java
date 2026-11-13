package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.Collection;

/** Redis 수량·중복 판단. R은 이번 실행의 예약이고 A는 실제 DB 발급이 확인된 사용자다. */
public interface CouponIssueGate {
    /**
     * CANCELLED는 작업 큐에서 확정 취소된 사용자(C)다. 같은 캠페인에 다시 받지 않는다.
     * SOLD_OUT은 남은 수가 0이지만 저장 전 예약이 남아 취소로 자리가 돌아올 수 있는 매진,
     * SOLD_OUT_FINAL은 저장 전 예약도 없어 다시 풀리지 않는 매진이다.
     */
    enum Decision { PASSED, ALREADY_ACCEPTED, ALREADY_RESERVED, CANCELLED, SOLD_OUT, SOLD_OUT_FINAL, ENDED, NOT_PREPARED }
    /** 사용자 기록. RESERVED는 R(예약), ACCEPTED는 A(DB 발급 확인), NONE은 기록 없음이다. */
    enum UserState { NONE, RESERVED, ACCEPTED }
    record Entry(Decision decision, String reservation) {}
    Entry enter(Long campaignId, Long userId, Instant now);
    /** enter와 같은 판단을 하고, 통과하면 같은 실행 안에서 작업 큐에 발급 작업을 등록한다. */
    Entry enqueue(Long campaignId, Long userId, Instant now);
    UserState state(Long campaignId, Long userId);
    /** 실제 새 발급이면 R→A. 기존 쿠폰 결과라면 예약 차감분만 한 번 돌리고 A로 바꾼다. */
    boolean confirm(Long campaignId, Long userId, String reservation, boolean created);
    /** 이번 실행의 R이 그대로일 때만 지우고 차감분을 돌린다. */
    void cancel(Long campaignId, Long userId, String reservation);
    /** 작업 큐의 확정 취소. 이번 작업의 R이 그대로일 때만 C로 바꾸고 차감분을 돌린다. C는 재신청을 막는다. */
    void cancelFinally(Long campaignId, Long userId, String reservation);
    /** 발급 실행이 없는 상태에서 DB 기준으로 준비한다. */
    void prepare(Long campaignId, int remaining, Collection<Long> issuedUserIds, Instant endAt, Instant expireAt);

}
