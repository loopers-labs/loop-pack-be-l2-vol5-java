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

    // 골격(Red): 돈의 상태 비종결·AUTO 우선 선택은 Green에서 채운다.
    @Query("select c.orderId from PaymentRefundCaseJpaEntity c"
        + " where c.moneyStatus in ('CHECKING', 'REFUND_REQUESTED', 'REFUND_UNKNOWN') and c.nextCheckAt <= :now"
        + " order by c.nextCheckAt, c.orderId")
    List<Long> findDue(@Param("now") Instant now, Pageable pageable);
}
