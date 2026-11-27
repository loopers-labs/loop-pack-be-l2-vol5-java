package com.loopers.application.payment;

import com.loopers.application.payment.dto.UnresolvedPaymentInfo;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.UnresolvedPayments;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/** 운영 지표용 결제 상태 조회. 미종결 상태를 빠짐없이 돌려준다(시도가 없으면 0). */
@RequiredArgsConstructor
@Component
public class PaymentMonitoringFacade {

    private final PaymentAttemptService paymentAttemptService;

    @Transactional(readOnly = true)
    public List<UnresolvedPaymentInfo> unresolved() {
        UnresolvedPayments unresolved = paymentAttemptService.summarizeUnresolved();
        // 생성 시각은 BaseEntity 가 시스템 시각으로 기록하므로 같은 시계로 잰다. 주입 Clock 은 테스트에서 고정될 수 있다.
        ZonedDateTime now = ZonedDateTime.now();
        return UnresolvedPayments.STATUSES.stream()
            .map(status -> new UnresolvedPaymentInfo(status, unresolved.countOf(status), unresolved.oldestAgeSeconds(status, now)))
            .toList();
    }
}
