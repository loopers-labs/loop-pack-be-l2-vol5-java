package com.loopers.domains.payment.out;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRefundCaseJpaRepository extends JpaRepository<PaymentRefundCaseJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentRefundCaseJpaEntity c where c.orderId = :orderId")
    Optional<PaymentRefundCaseJpaEntity> findForUpdate(@Param("orderId") long orderId);

    // 담당과 무관하게 돈의 상태가 끝나지 않은 대상을 고른다(사람 담당이어도 PG 사실은 계속 확인한다, 계획 3절).
    // 회차 상한은 자동·사람 담당 합계이고 자동 담당을 먼저 채운다. (money_status, next_check_at) 인덱스로 상태별 범위를 읽는다.
    @Query("select c.orderId from PaymentRefundCaseJpaEntity c"
        + " where c.moneyStatus in ('CHECKING', 'CHARGED', 'REFUND_REQUESTED', 'REFUND_UNKNOWN') and c.nextCheckAt <= :now"
        + " order by case when c.owner = 'AUTO' then 0 else 1 end, c.nextCheckAt, c.orderId")
    List<Long> findDue(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("update PaymentRefundCaseJpaEntity c set c.lastCheckAt = :checkedAt, c.lastCheckOutcome = :outcome,"
        + " c.lastCheckCause = :cause where c.orderId = :orderId")
    int updateLastCheck(@Param("orderId") long orderId, @Param("outcome") String outcome, @Param("cause") String cause,
        @Param("checkedAt") Instant checkedAt);
}
