package com.loopers.application.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// 설정으로 켰을 때 스케줄러가 고정 간격으로 주기 조회를 실행하는지만 확인한다. 조회 내용은 PaymentPeriodicCheck 테스트가 맡는다.
@SpringBootTest(properties = {
    "payment.periodic-check.enabled=true",
    "payment.periodic-check.interval=100ms"
})
class PaymentCheckSchedulerTest {

    @MockitoBean
    private PaymentPeriodicCheck paymentPeriodicCheck;

    // 설정으로 켜면 스케줄러가 등록된다. 등록되지 않으면 주입에 실패한다.
    @Autowired
    private PaymentCheckScheduler paymentCheckScheduler;

    @DisplayName("주기 조회를 켜면 설정한 간격마다 미종결 시도를 확인한다.")
    @Test
    void runsPeriodicCheckRepeatedly() {
        verify(paymentPeriodicCheck, timeout(3_000).atLeast(2)).checkDue();
    }
}
