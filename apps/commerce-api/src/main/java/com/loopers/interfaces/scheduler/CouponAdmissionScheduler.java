package com.loopers.interfaces.scheduler;

import com.loopers.application.coupon.CouponAdmissionFacade;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionSchedulingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 승격·gate probe 의 주기 실행 (8주차 설계 §5-2 D2, §7-3 D10).
 * - enabled 는 인스턴스별 로컬 설정이다. test 프로파일은 false 로 두고 promoteOnce()/probeOnce() 를 직접 호출한다
 * - 두 작업 모두 쿠폰 전용 단일 스레드 스케줄러에서 돈다 (CouponAdmissionSchedulingConfig). 하트비트도 같은 스레드를 쓴다
 * - 다중 인스턴스에서 동시에 돌아도 승격 Lua 가 원자적이라 C 를 넘기지 않는다 (§5-2). 분산 잠금은 두지 않는다
 */
@RequiredArgsConstructor
@Component
public class CouponAdmissionScheduler {

    private final CouponAdmissionFacade facade;
    private final CouponAdmissionProperties properties;

    @Scheduled(
        fixedDelayString = "${coupon.admission.promote-fixed-delay}",
        scheduler = CouponAdmissionSchedulingConfig.COUPON_ADMISSION_TASK_SCHEDULER
    )
    public void promote() {
        if (!properties.enabled()) {
            return;
        }
        facade.promoteOnce();
    }

    /** 자동 재개가 꺼져 있으면(기본) 즉시 돌아간다. 설정으로 켠 경우에만 REDIS_ERROR 로 닫힌 gate 의 세대를 확인한다. */
    @Scheduled(
        fixedDelayString = "${coupon.admission.auto-reopen.probe-interval}",
        scheduler = CouponAdmissionSchedulingConfig.COUPON_ADMISSION_TASK_SCHEDULER
    )
    public void probe() {
        if (!properties.enabled() || !properties.autoReopen().enabled()) {
            return;
        }
        facade.probeOnce();
    }
}
