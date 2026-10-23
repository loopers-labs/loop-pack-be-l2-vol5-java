package com.loopers.interfaces.scheduling;

import com.loopers.application.order.PaymentRecoveryBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "payment.recovery.scheduling-enabled", havingValue = "true")
public class PaymentRecoveryScheduler {
    private final PaymentRecoveryBatch batch;

    // 앞 실행이 끝난 뒤 1분을 기다려 실행이 겹치지 않는다.
    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        log.info("Payment recovery finished: {}", batch.run());
    }
}
