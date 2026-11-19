package com.loopers.domains.coupon.out;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponAdmissionSettingsJpaRepository extends JpaRepository<CouponAdmissionSettingsJpaEntity, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CouponAdmissionSettingsJpaEntity s set s.open = false, s.closedReason = :reason, s.closedAt = :at,"
        + " s.updatedAt = :at where s.id = :id")
    int close(@Param("id") long id, @Param("reason") String reason, @Param("at") Instant at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CouponAdmissionSettingsJpaEntity s set s.open = true, s.epoch = s.epoch + 1, s.closedReason = null,"
        + " s.closedAt = null, s.updatedAt = :at where s.id = :id and s.open = false")
    int open(@Param("id") long id, @Param("at") Instant at);
}
