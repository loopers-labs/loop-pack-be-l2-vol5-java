package com.loopers.infrastructure.payment.persistence;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findAllByOrderIdIn(Collection<Long> orderIds);

    /** [status, count] 행. 행이 없는 상태는 결과에 없음 */
    @Query("select p.status, count(p) from Payment p where p.status in :statuses group by p.status")
    List<Object[]> countByStatusIn(@Param("statuses") Collection<PaymentStatus> statuses);
}
