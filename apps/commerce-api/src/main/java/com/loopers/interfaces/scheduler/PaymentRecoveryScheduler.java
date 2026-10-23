package com.loopers.interfaces.scheduler;

import com.loopers.application.payment.PaymentRecoveryJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * 결제 회복 작업의 시간 입구 — HTTP 요청 대신 시계가 부르는 controller에 해당한다(docs/week5/design.md D-040).
 * 앞 회차가 끝난 뒤 간격을 두고 다시 돈다(fixedDelay) — 한 회차가 길어져도 겹치지 않는다.
 * 서버 한 대 기준이다(여러 대 중복 실행 방지는 보류, docs/week5/design.md 7번). 테스트에서는 꺼 둔다 — 백그라운드 회차가 테스트 데이터를 같이 처리하지 않게.
 */
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Component
public class PaymentRecoveryScheduler {

    private final PaymentRecoveryJob paymentRecoveryJob;

    @Scheduled(fixedDelayString = "${payment.recovery.interval:PT3M}", initialDelayString = "${payment.recovery.interval:PT3M}")
    public void run() {
        paymentRecoveryJob.runOnce(ZonedDateTime.now());
    }
}
