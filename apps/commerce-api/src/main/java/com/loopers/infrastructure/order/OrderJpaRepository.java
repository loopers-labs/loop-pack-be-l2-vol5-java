package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderModel o where o.id = :id")
    Optional<OrderModel> findForUpdate(@Param("id") Long id);

    List<OrderModel> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<OrderModel> findAllByOrderByCreatedAtDesc();
}
