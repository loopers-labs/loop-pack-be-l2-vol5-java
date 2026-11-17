package com.loopers.infrastructure.persistence.coupon.jpa;

import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponCampaignJpaRepository extends JpaRepository<CouponCampaignJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCampaignJpaEntity c where c.id = :id")
    Optional<CouponCampaignJpaEntity> findByIdForUpdate(@Param("id") long id);
}
