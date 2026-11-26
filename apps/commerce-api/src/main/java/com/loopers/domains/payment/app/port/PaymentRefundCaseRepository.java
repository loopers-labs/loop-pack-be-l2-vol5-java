package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.RefundCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundCaseRepository {
    RefundCase save(RefundCase refundCase);

    Optional<RefundCase> findByOrderId(long orderId);

    // 잠금 없는 읽기다. 행이 없을 때 FOR UPDATE를 피하려는 존재 확인에만 쓴다.
    boolean existsByOrderId(long orderId);

    Optional<RefundCase> findByOrderIdForUpdate(long orderId);

    List<Long> findDue(Instant now, int limit);

    // 마지막 확인 칸만 바꾼다. 반영 트랜잭션이 rollback된 뒤 원인만 남길 때 쓴다(돈·담당 칸은 건드리지 않는다).
    void recordCheck(long orderId, RefundCase.CheckOutcome outcome, String cause, Instant checkedAt);
}
