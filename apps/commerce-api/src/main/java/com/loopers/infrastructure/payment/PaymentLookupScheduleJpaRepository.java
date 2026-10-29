package com.loopers.infrastructure.payment;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentLookupScheduleJpaRepository extends JpaRepository<PaymentLookupScheduleJpaEntity, Long> {
    @Query("select s.orderId from PaymentLookupScheduleJpaEntity s where s.nextLookupAt <= :now order by s.nextLookupAt, s.orderId")
    List<Long> findDue(@Param("now") Instant now, Pageable pageable);
}
