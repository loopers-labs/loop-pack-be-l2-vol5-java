package com.loopers.infrastructure.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface PointJpaRepository extends JpaRepository<PointJpaEntity, Long> {
    Optional<PointJpaEntity> findByUserId(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PointJpaEntity p
        SET p.balance = p.balance + :amount,
            p.updatedAt = :updatedAt
        WHERE p.userId = :userId
          AND p.balance <= :maximumBalance - :amount
        """)
    int increaseBalanceIfWithinMaximum(
        @Param("userId") Long userId,
        @Param("amount") long amount,
        @Param("maximumBalance") long maximumBalance,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PointJpaEntity p
        SET p.balance = p.balance - :amount,
            p.updatedAt = :updatedAt
        WHERE p.userId = :userId
          AND p.balance >= :amount
        """)
    int decreaseBalanceIfEnough(
        @Param("userId") Long userId,
        @Param("amount") long amount,
        @Param("updatedAt") ZonedDateTime updatedAt
    );
}
