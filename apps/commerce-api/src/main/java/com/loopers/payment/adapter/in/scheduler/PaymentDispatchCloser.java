package com.loopers.payment.adapter.in.scheduler;

import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * 놓친 발송 정리의 방아쇠 (plan.md 5-1, ADR-W5-01·03). 1분마다(fixedDelay라 겹치지 않음) 시작만 한다. 판단은 PaymentRecoveryService가 한다.
 * payment.scheduler.enabled=false(test 프로필)면 빈을 만들지 않아, 테스트는 서비스를 직접 부른다.
 */
@ConditionalOnProperty(name = "payment.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
@Component
public class PaymentDispatchCloser {

    private final PaymentRecoveryUseCase paymentRecoveryUseCase;

    @Scheduled(fixedDelayString = "${payment.scheduler.close-delay:60s}", initialDelayString = "${payment.scheduler.close-delay:60s}")
    public void close() {
        paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now());
    }
}
