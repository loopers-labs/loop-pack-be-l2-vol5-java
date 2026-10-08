package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {
    List<OrderJpaEntity> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    Page<OrderJpaEntity> findByUserId(long userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update OrderJpaEntity o set o.status = :confirmed, o.paidAmount = :paidAmount, "
                    + "o.paymentResult = :paymentResult, o.updatedAt = :now "
                    + "where o.id = :id and o.userId = :userId and o.status = :draft")
    int confirmIfDraft(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("draft") OrderStatus draft,
            @Param("confirmed") OrderStatus confirmed,
            @Param("paidAmount") long paidAmount,
            @Param("paymentResult") String paymentResult,
            @Param("now") ZonedDateTime now);
}
