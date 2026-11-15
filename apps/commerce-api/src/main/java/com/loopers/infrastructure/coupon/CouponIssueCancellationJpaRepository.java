package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueCancellationModel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueCancellationJpaRepository extends JpaRepository<CouponIssueCancellationModel, Long> {
    Optional<CouponIssueCancellationModel> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<CouponIssueCancellationModel> findAllByCampaignIdAndUserIdIn(Long campaignId, Collection<Long> userIds);
}
