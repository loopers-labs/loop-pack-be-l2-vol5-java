package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

// 콜백 유실·생성 응답 유실·결과 반영 실패·재시작으로 남은 미종결 시도를 시스템이 다시 확인한다.
// 시도마다 reconcile과 같은 결과 반영 경로를 쓰며, 생성 요청은 보내지 않는다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentPeriodicCheck {
    private final PaymentPeriodicCheckProperties properties;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultTransaction paymentResultTransaction;
    private final PaymentResultService paymentResultService;

    // 마지막 상태 변경 뒤 기준 시간이 지났고 조회 대상 기간 안의 미종결 시도를, 확인 시각이 오래된 순서로 정해진 건수만 조회한다.
    @Transactional(propagation = Propagation.NEVER)
    public int checkDue() {
        ZonedDateTime now = ZonedDateTime.now();
        List<PaymentAttempt> due = paymentAttemptRepository.findDueForCheck(
            now.minus(properties.idleThreshold()), now.minus(properties.lookupWindow()), properties.batchSize()
        );
        due.forEach(attempt -> check(attempt, now));
        return due.size();
    }

    // 결과가 나오지 않는 시도가 다른 시도의 조회를 막지 않도록 조회 전에 확인 시각부터 남긴다.
    // 한 시도의 실패는 그 시도만 건너뛰고 다음 실행에서 다시 확인한다.
    private void check(PaymentAttempt attempt, ZonedDateTime checkedAt) {
        try {
            paymentResultTransaction.markChecked(attempt.getId(), checkedAt);
            PaymentReflection reflection = paymentResultService.check(attempt.getId());
            if (reflection.outcome() == PaymentReflectionOutcome.LOOKUP_UNAVAILABLE) {
                log.warn("주기 조회에서 PG를 조회하지 못해 다음 실행에서 다시 확인합니다. attemptId={}", attempt.getId());
            }
        } catch (RuntimeException exception) {
            log.error("주기 조회에서 결제 결과를 반영하지 못해 다음 실행에서 다시 확인합니다. attemptId={}, orderId={}, cause={}",
                attempt.getId(), attempt.getOrderId(), exception.toString());
        }
    }
}
