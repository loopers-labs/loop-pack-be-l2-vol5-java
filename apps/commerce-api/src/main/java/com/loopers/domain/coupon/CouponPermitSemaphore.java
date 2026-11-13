package com.loopers.domain.coupon;

/**
 * 동시에 DB 발급을 실행할 수 있는 자리 C 개 (8주차 설계 §5-3·§7-2, INV-W8-01).
 * 자리는 permits ZSET 의 token 이며 score 는 마지막 하트비트 시각이다. 하트비트가 끊긴 자리는 TTL 뒤 Lua 가 회수한다 (D4).
 * 저장소 오류는 잡지 않고 전파한다. 호출자가 gate 를 닫고 503 으로 끝낸다 (§7-1).
 */
public interface CouponPermitSemaphore {

    enum ClaimOutcome {
        /** 자리를 얻었다. 호출자는 하트비트를 시작하고 DB 발급으로 간다 */
        ACQUIRED,
        /** 자리가 모두 찼다 (429). 정상 운영에서는 승격이 C 를 지키므로 거의 나지 않는 방어선 */
        NO_PERMIT,
        /** 승격되지 않았거나 승격이 만료됐다 (409) */
        NOT_ADMITTED,
        /** Redis 세대값이 기억한 값과 다르다. 호출자는 gate 를 닫는다 (503) */
        STALE_GENERATION
    }

    /**
     * 세마포어 Lua. 세대 확인 → 만료 token 회수 → 승격 확인 → 자기 token 재확인 → ZCARD < C 이면 ZADD permits now token.
     *
     * @param token 이 실행을 식별하는 UUID. 반납 때 자기 것만 지우기 위해 쓴다
     */
    ClaimOutcome tryAcquire(Long campaignId, Long userId, String token, String expectedGen);

    /**
     * 자기 token 의 score 를 현재 시각으로 갱신한다. ZADD XX. token 이 이미 회수됐으면 되살리지 않는다 (INV-W8-11).
     *
     * @return score 가 갱신됐으면 true. token 이 없거나(회수됨), 직전 갱신과 같은 밀리초라 score 가 그대로면 false.
     *         후자는 하트비트 주기(≥200ms)에서는 나지 않는다. 호출자는 false 를 warn 로그로만 남긴다 (D4-2)
     */
    boolean heartbeat(Long campaignId, String token);

    /**
     * 자기 token 만 지운다. ZREM permits token. 다른 자리에는 영향이 없다 (INV-W8-04).
     *
     * @return 지웠으면 true. 이미 회수돼 없었으면 false (호출자는 recovered 로 집계한다, D4-1)
     */
    boolean release(Long campaignId, String token);

    /** 현재 permits 크기. ZCARD. 누수 감시 Gauge(coupon.admission.permits)의 값이다 (§7-2). */
    long permitCount(Long campaignId);
}
