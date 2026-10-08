package com.loopers.infrastructure.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface PointBalanceJpaRepository extends JpaRepository<PointBalanceJpaEntity, Long> {
    Optional<PointBalanceJpaEntity> findByUserId(long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "insert into point_balances (user_id, balance, created_at, updated_at) "
                            + "values (:userId, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) "
                            + "on duplicate key update user_id = user_id",
            nativeQuery = true)
    void initializeIfAbsent(@Param("userId") long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update PointBalanceJpaEntity p set p.balance = p.balance + :amount, p.updatedAt = :now "
                    + "where p.userId = :userId and p.balance <= :maximumBeforeCharge")
    int charge(
            @Param("userId") long userId,
            @Param("amount") long amount,
            @Param("maximumBeforeCharge") long maximumBeforeCharge,
            @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update PointBalanceJpaEntity p set p.balance = p.balance - :amount, p.updatedAt = :now "
                    + "where p.userId = :userId and p.balance >= :amount")
    int deduct(
            @Param("userId") long userId,
            @Param("amount") long amount,
            @Param("now") ZonedDateTime now);
}
