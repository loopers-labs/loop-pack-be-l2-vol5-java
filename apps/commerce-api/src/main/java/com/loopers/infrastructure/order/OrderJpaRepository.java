package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {
    Optional<OrderModel> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderModel o where o.id = :id and o.userId = :userId")
    Optional<OrderModel> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    Page<OrderModel> findAllByUserId(Long userId, Pageable pageable);
}
