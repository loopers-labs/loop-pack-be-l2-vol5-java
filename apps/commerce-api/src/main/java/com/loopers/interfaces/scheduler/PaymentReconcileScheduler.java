package com.loopers.interfaces.scheduler;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentLookupProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미확인 결제 시도의 주기 조회 진입점 (설계 5-3 결정 ⑫, 6장). 컨트롤러와 같은 "진입점"이라 interfaces 계층에 둔다.
 * 로직은 없다. payment.lookup.enabled 가 true 일 때만 PaymentFacade 의 1회 실행 메서드를 호출한다.
 * enabled=false 여도 이 빈은 만들어진다. 테스트는 자동 실행을 끄고 Facade 메서드를 직접 호출한다.
 */
@RequiredArgsConstructor
@Component
public class PaymentReconcileScheduler {

    private final PaymentFacade paymentFacade;
    private final PaymentLookupProperties lookupProperties;

    @Scheduled(fixedDelayString = "${payment.lookup.fixed-delay}")
    public void run() {
        if (!lookupProperties.enabled()) {
            return;
        }
        paymentFacade.runScheduledLookups();
    }
}
