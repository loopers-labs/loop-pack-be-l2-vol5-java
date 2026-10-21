package com.loopers.payment.adapter.in.scheduler;

import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * 결과를 모르는 결제 확인의 방아쇠 (plan.md 5-1). 5초마다 다음 조회 시각이 된 결제를 PG 조회로 확인하게 한다. 결제를 생성하지 않는다.
 * payment.scheduler.enabled=false(test 프로필)면 빈을 만들지 않는다.
 */
@ConditionalOnProperty(name = "payment.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
@Component
public class PaymentReconcileScheduler {

    private final PaymentRecoveryUseCase paymentRecoveryUseCase;

    @Scheduled(fixedDelayString = "${payment.scheduler.reconcile-delay:5s}", initialDelayString = "${payment.scheduler.reconcile-delay:5s}")
    public void reconcile() {
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now());
    }
}
