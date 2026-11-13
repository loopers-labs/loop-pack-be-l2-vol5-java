package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * 접수 대기열과 승격 상태 (8주차 설계 §4·§5-1·§5-2).
 * 저장소 오류(Redis 예외·타임아웃)는 잡지 않고 전파한다. 호출자가 gate 를 닫고 503 으로 끝낸다 (§7-1).
 */
public interface CouponAdmissionQueue {

    enum EnqueueOutcome {
        /** 신규 접수. seq 는 접수 번호 (202) */
        ACCEPTED,
        /** 이미 접수됨. 대기 중이면 seq 는 첫 접수 번호, 승격됐으면 0 (200) */
        ALREADY_ACCEPTED,
        /** 접수 상한 도달. 번호를 소비하지 않는다 (409) */
        SOLD_OUT,
        /** Redis 세대값이 기억한 값과 다르다. 호출자는 gate 를 닫는다 (503) */
        STALE_GENERATION
    }

    record EnqueueResult(EnqueueOutcome outcome, long seq) { }

    /**
     * 접수 Lua. 세대 확인 → 중복 확인(waiting·admitted) → 상한 비교 → INCR → ZADD waiting 을 한 덩어리로 실행한다 (§5-1).
     *
     * @param acceptLimit 접수 상한 (총량 × 여유배수, D7)
     * @param expectedGen gate 가 기억한 세대값
     */
    EnqueueResult enqueue(Long campaignId, Long userId, long acceptLimit, String expectedGen);

    /** 대기열 순위 (0 부터). 대기열에 없으면 비어 있다. ZRANK. */
    Optional<Long> waitingRank(Long campaignId, Long userId);

    /** 승격된 사용자인지. ZSCORE admitted. */
    boolean isAdmitted(Long campaignId, Long userId);

    /** 지금까지 발급된 접수 번호 수. GET seq. 접수가 없으면 0. */
    long acceptedCount(Long campaignId);

    /** 대기열 크기. ZCARD waiting. 관리자 상태 조회용 (§8-1). */
    long waitingCount(Long campaignId);

    /** 승격된 사용자 수. ZCARD admitted. 관리자 상태 조회용 (§8-1). */
    long admittedCount(Long campaignId);

    /**
     * 승격 Lua. 세대 확인 → admitted·permits 만료 정리 → 빈자리만큼 ZPOPMIN waiting → ZADD admitted (§5-2).
     *
     * @return 승격한 사용자 수. -1 이면 세대 불일치이며 호출자는 승격을 중단하고 gate 를 닫는다
     */
    int promote(Long campaignId, String expectedGen);

    /**
     * 발급 종료 후 승격 예약을 지운다. ZREM admitted.
     *
     * @return 지웠으면 true. 이미 만료 정리로 없어졌으면 false
     */
    boolean removeAdmitted(Long campaignId, Long userId);
}
