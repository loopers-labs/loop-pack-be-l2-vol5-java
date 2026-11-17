package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.domain.CouponIssueOutbox;
import com.loopers.coupon.domain.CouponIssueOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface CouponIssueOutboxJpaRepository extends JpaRepository<CouponIssueOutbox, Long> {

    @Query("SELECT o FROM CouponIssueOutbox o WHERE o.status IN :statuses AND o.nextAttemptAt <= :now ORDER BY o.id")
    List<CouponIssueOutbox> findDue(
        @Param("statuses") Collection<CouponIssueOutboxStatus> statuses,
        @Param("now") ZonedDateTime now,
        Pageable pageable
    );

    @Query("SELECT o.userId FROM CouponIssueOutbox o WHERE o.campaignId = :campaignId")
    List<Long> findUserIds(@Param("campaignId") Long campaignId);

    @Query("SELECT o.userId FROM CouponIssueOutbox o WHERE o.campaignId = :campaignId AND o.userId IN :userIds")
    List<Long> findRecordedUserIds(@Param("campaignId") Long campaignId, @Param("userIds") Collection<Long> userIds);
}
