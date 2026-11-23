package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 상태의 현재 건수를 읽는 읽기 전용 조회(checkout-incident §3). 업무 지표 수집마다 호출된다.
 * DB 예외는 그대로 전파하며 0 으로 바꾸지 않는다. 쿼리·TX timeout 1초는 목표이며 연결 획득 대기를 포함한 상한이 아니다.
 */
@RequiredArgsConstructor
@Component
public class PaymentStatusQuery {

    private final PaymentRepository paymentRepository;

    /** 현재 UNKNOWN 결제 행 수. PENDING 은 포함하지 않는다. 음수는 내부 오류다. */
    @Transactional(readOnly = true, timeout = 1)
    public long countUnknown() {
        long count = paymentRepository.countByStatus(PaymentStatus.UNKNOWN);
        if (count < 0) {
            throw new IllegalStateException("UNKNOWN 결제 건수가 음수");
        }
        return count;
    }
}
