package com.loopers.application.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 주기 조회를 고정 간격으로 실행한다. 이전 실행이 끝난 뒤 간격을 두므로 실행이 겹치지 않는다.
// test 프로필에서는 등록하지 않고 테스트가 PaymentPeriodicCheck를 직접 호출한다.
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "payment.periodic-check", name = "enabled", havingValue = "true")
public class PaymentCheckScheduler {
    private final PaymentPeriodicCheck paymentPeriodicCheck;

    @Scheduled(fixedDelayString = "${payment.periodic-check.interval}", initialDelayString = "${payment.periodic-check.interval}")
    public void run() {
        paymentPeriodicCheck.checkDue();
    }
}
