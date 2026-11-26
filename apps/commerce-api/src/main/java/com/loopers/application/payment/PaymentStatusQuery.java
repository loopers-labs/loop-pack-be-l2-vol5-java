package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * UNKNOWN 결제 건수 조회(checkout-incident §3).
 * 쿼리·TX timeout 1초는 연결 획득 대기를 포함한 전체 응답 상한이 아니다.
 */
@RequiredArgsConstructor
@Component
public class PaymentStatusQuery {

    private final PaymentRepository paymentRepository;

    /** 음수는 내부 오류로 거절하고 DB 예외는 그대로 전파한다. */
    @Transactional(readOnly = true, timeout = 1)
    public long countUnknown() {
        long count = paymentRepository.countByStatus(PaymentStatus.UNKNOWN);
        if (count < 0) {
            throw new IllegalStateException("UNKNOWN 결제 건수가 음수");
        }
        return count;
    }
}
