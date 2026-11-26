package com.loopers.domains.payment.out;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRefundCaseJpaRepository extends JpaRepository<PaymentRefundCaseJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentRefundCaseJpaEntity c where c.orderId = :orderId")
    Optional<PaymentRefundCaseJpaEntity> findForUpdate(@Param("orderId") long orderId);

    // 결론 상태는 고르지 않는다. (status, next_check_at) 인덱스로 상태별 범위를 읽는다.
    @Query("select c.orderId from PaymentRefundCaseJpaEntity c"
        + " where c.status in ('TRACKING', 'REFUND_REQUESTED', 'REFUND_UNKNOWN') and c.nextCheckAt <= :now"
        + " order by c.nextCheckAt, c.orderId")
    List<Long> findDue(@Param("now") Instant now, Pageable pageable);
}
