package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderModel o where o.id = :id")
    Optional<OrderModel> findByIdForUpdate(@Param("id") Long id);

    /** ... order by payment_started_at limit 1 for update skip locked (lock timeout -2 = Hibernate LockOptions.SKIP_LOCKED). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    Optional<OrderModel> findFirstByStatusAndPaymentStartedAtLessThanEqualOrderByPaymentStartedAtAsc(
        OrderStatus status, ZonedDateTime startedBefore);
}
