package com.loopers.domain.payment;

import java.util.List;

public interface PaymentConflictRepository {

    PaymentConflict save(PaymentConflict conflict);

    /**
     * 같은 내용의 충돌이 이미 있는지 판단하려고 시도의 충돌을 모두 읽는다(D-012).
     * 결과 반영 트랜잭션이 결제 시도 행을 잠근 뒤 부르므로, 동시에 발견된 같은 충돌도 한 행으로 모인다.
     */
    List<PaymentConflict> findAllByAttemptId(Long attemptId);
}
