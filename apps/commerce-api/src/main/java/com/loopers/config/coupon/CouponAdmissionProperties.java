package com.loopers.config.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 쿠폰 발급 수용량 통제 설정 (8주차 설계 §5·§7-2·§7-3·D4-4·D5·D7·D10). 값은 application.yml 의 coupon.admission 에서 바인딩된다.
 * - capacity: 동시에 DB 발급 트랜잭션을 실행할 수 있는 자리 수 C
 * - promoteFixedDelay: 승격 스케줄러 주기. enabled 가 false 면 스케줄러 빈은 만들고 자동 실행만 끈다 (테스트는 promoteOnce() 직접 호출)
 * - admittedTtl: 승격 후 claim 을 보내지 않는 사용자의 예약을 취소하기까지의 시간
 * - heartbeatInterval / permitTtl: 실행 중 자리의 하트비트 주기와, 하트비트가 끊긴 자리를 회수하기까지의 시간. TTL 은 주기의 3배
 * - acceptLimitMultiplier: 접수 상한 = 총량 × 이 배수. DB 실패분을 흡수한다
 * - autoReopen: 조건부 자동 재개. 기본은 꺼짐(수동 재개). 켜면 REDIS_ERROR 로 닫힌 gate 를 probe 가 세대 일치 연속 확인 뒤 연다
 */
@ConfigurationProperties(value = "coupon.admission")
public record CouponAdmissionProperties(
    boolean enabled,
    int capacity,
    Duration promoteFixedDelay,
    Duration admittedTtl,
    Duration heartbeatInterval,
    Duration permitTtl,
    double acceptLimitMultiplier,
    AutoReopen autoReopen
) {
    /**
     * @param enabled           false 면 probe 를 돌리지 않고 관리자 open 으로만 연다
     * @param probeInterval     닫힌 동안 GET gen 을 확인하는 주기
     * @param requiredSuccesses 연속으로 세대가 일치해야 하는 횟수
     * @param maxReopens        window 안에 허용하는 자동 재개 횟수. 넘으면 FLAPPING
     * @param window            maxReopens 를 세는 시간 창
     */
    public record AutoReopen(
        boolean enabled,
        Duration probeInterval,
        int requiredSuccesses,
        int maxReopens,
        Duration window
    ) { }
}
