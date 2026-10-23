package com.loopers.domain.payment;

import java.util.Optional;

/** 마지막 충돌 요약 저장 포트. 같은 Order 잠금 아래 일반 조회 뒤 생성·교체한다. */
public interface PaymentConflictRepository {

    Optional<PaymentConflictModel> findByPaymentId(Long paymentId);

    PaymentConflictModel save(PaymentConflictModel conflict);
}
