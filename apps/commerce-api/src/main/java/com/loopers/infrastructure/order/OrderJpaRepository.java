package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {
    List<OrderModel> findAllByUserId(Long userId);

    List<OrderModel> findAllByStatusAndExpiredAtBefore(OrderStatus status, ZonedDateTime now);

    List<OrderModel> findAllByStatus(OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderModel o where o.id = :id")
    Optional<OrderModel> findByIdWithLock(@Param("id") Long id);
}
