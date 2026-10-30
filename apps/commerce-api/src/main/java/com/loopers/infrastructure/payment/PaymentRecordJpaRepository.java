package com.loopers.infrastructure.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRecordJpaRepository extends JpaRepository<PaymentRecordJpaEntity, Long> {
    Optional<PaymentRecordJpaEntity> findByOrderId(long orderId);

    List<PaymentRecordJpaEntity> findByOrderIdIn(Collection<Long> orderIds);

    Optional<PaymentRecordJpaEntity> findByProviderOrderId(String providerOrderId);

    Optional<PaymentRecordJpaEntity> findByAttemptKey(String attemptKey);

    @Query("select p.orderId from PaymentRecordJpaEntity p where p.result = 'PENDING' and p.method = 'PG'"
        + " and p.startedAt <= :startedAtOrBefore order by p.startedAt, p.orderId")
    List<Long> findExpirable(@Param("startedAtOrBefore") Instant startedAtOrBefore, Pageable pageable);
}
