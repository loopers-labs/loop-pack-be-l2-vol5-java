package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.Optional;

/**
 * 캠페인별 신규 접수·승격·claim 허용 여부 (8주차 설계 §7-1·§7-3, D10·D14).
 * 인스턴스 메모리 플래그다. 기본값은 닫힘이며 관리자 open 으로 열린다. 자동 재개 설정을 켠 경우에만 조건부 자동 재개(probeOnce)가 추가된다 (INV-W8-10).
 * open 은 새 세대값(UUID)을 만들어 Redis 에 쓰고 메모리에 기억한다. 접수·세마포어·승격 Lua 는 이 값을 Redis 의 값과 비교해 상태 유실을 감지한다 (INV-W8-12).
 * open 은 기존 키(seq·waiting·admitted·permits)를 지우지 않는다. 기존 대기자는 세대가 바뀌어도 이어서 승격된다 (§7-3).
 */
public interface CouponAdmissionGate {

    /** 닫힌 이유. 자동 재개 대상은 REDIS_ERROR 만이다 (§7-3). */
    enum CloseReason {
        /** 관리자 close */
        ADMIN,
        /** Redis 예외·타임아웃 */
        REDIS_ERROR,
        /** Redis 세대값이 기억한 값과 다르다 (Lua 또는 probe 가 감지) */
        STALE_GENERATION,
        /** Lua 반환값을 해석할 수 없다 */
        UNEXPECTED_RESULT,
        /** window 안에 자동 재개가 max-reopens 를 넘었다. 관리자 open 을 기다린다 */
        FLAPPING
    }

    record Closure(CloseReason reason, Instant closedAt) { }

    boolean isOpen(Long campaignId);

    /**
     * 열려 있을 때 기억하고 있는 세대값. 닫혀 있거나 한 번도 열지 않았으면 비어 있다.
     */
    Optional<String> generation(Long campaignId);

    /** 닫혀 있을 때 그 이유와 시각. 열려 있거나 한 번도 열지 않았으면 비어 있다. */
    Optional<Closure> closure(Long campaignId);

    /**
     * 새 세대값을 만들어 Redis 에 쓰고 gate 를 연다. Redis 쓰기가 실패하면 gate 는 닫힌 채로 예외가 전파된다.
     *
     * @return 새 세대값
     */
    String open(Long campaignId);

    /** 관리자가 닫는다 (ADMIN). */
    void close(Long campaignId);

    /** Redis 예외·Lua 해석 불가·세대 불일치를 감지한 요청 경로가 닫는다 (fail-closed). */
    void forceClose(Long campaignId, CloseReason reason);

    /**
     * 조건부 자동 재개 한 번 (§7-3). 자동 재개 설정이 꺼져 있거나, 열려 있거나, REDIS_ERROR 외의 이유로 닫혔으면 아무 일도 하지 않는다.
     * GET gen 이 기억한 세대와 연속 required-successes 회 일치하면 세대를 바꾸지 않고 연다.
     * 불일치면 이유를 STALE_GENERATION 으로, window 안 재개 횟수가 max-reopens 를 넘으면 FLAPPING 으로 바꾸고 멈춘다. 예외는 연속 횟수를 0 으로 되돌린다.
     *
     * @return 이 호출로 열렸으면 true
     */
    boolean probeOnce(Long campaignId);
}
