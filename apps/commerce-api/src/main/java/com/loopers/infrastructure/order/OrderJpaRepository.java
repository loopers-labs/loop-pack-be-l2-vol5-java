package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderJpaEntity o where o.id = :id")
    Optional<OrderJpaEntity> findByIdForUpdate(@Param("id") long id);

    List<OrderJpaEntity> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    Page<OrderJpaEntity> findByUserId(long userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderJpaEntity o set o.status = :pending, o.updatedAt = :now "
            + "where o.id = :id and o.userId = :userId and o.status = :draft")
    int awaitPaymentIfDraft(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("draft") OrderStatus draft,
            @Param("pending") OrderStatus pending,
            @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update OrderJpaEntity o set o.status = :confirmed, o.paidAmount = :paidAmount, "
                    + "o.paymentResult = :paymentResult, o.updatedAt = :now "
                    + "where o.id = :id and o.userId = :userId and o.status = :expectedStatus")
    int confirmIfStatus(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("expectedStatus") OrderStatus expectedStatus,
            @Param("confirmed") OrderStatus confirmed,
            @Param("paidAmount") long paidAmount,
            @Param("paymentResult") String paymentResult,
            @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderJpaEntity o set o.status = :failed, o.paymentResult = :paymentResult, o.updatedAt = :now "
            + "where o.id = :id and o.userId = :userId and o.status = :pending")
    int failIfPaymentPending(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("pending") OrderStatus pending,
            @Param("failed") OrderStatus failed,
            @Param("paymentResult") String paymentResult,
            @Param("now") ZonedDateTime now);
}
