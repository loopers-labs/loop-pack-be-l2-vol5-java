package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface CouponIssueDeadLetterJpaRepository extends JpaRepository<CouponIssueDeadLetterEntity, Long> {

    List<CouponIssueDeadLetterEntity> findTop100ByOrderByReceivedAtDescIdDesc();

    @Modifying
    @Query("update CouponIssueDeadLetterEntity d set d.status = :replayed, d.replayedAt = :at "
        + "where d.id = :id and d.status = :pending")
    int markReplayed(@Param("id") Long id, @Param("at") Instant at,
                     @Param("pending") CouponIssueDeadLetter.Status pending,
                     @Param("replayed") CouponIssueDeadLetter.Status replayed);
}
